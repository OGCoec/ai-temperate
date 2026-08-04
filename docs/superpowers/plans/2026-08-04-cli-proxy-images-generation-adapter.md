# CLIProxyAPI Images Generation Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 GPT Image 2 与 GPT Image 1.5 从错误的 `/v1/responses` 图片工具调用切换到 CLIProxyAPI `/v1/images/generations`，同时保留现有前端预览、最终 OSS 持久化、数据库 URL、异步终态和计费流程。

**Architecture:** 只替换后端上游适配层：产品档位服务生成合法质量与尺寸，请求工厂构造 Images Generation 请求，SSE Mapper 把 Images 事件重新映射为项目既有 `PARTIAL`、`FINAL` 和 `AiConversationUsage`。前端和后半段只依赖项目内部事件，不感知 CLIProxyAPI 协议变化，因此不修改前端、OSS、数据库或 Generation Worker。

**Tech Stack:** Java 21、Spring Boot、Spring WebFlux `WebClient`、Jackson、Reactor、JUnit 5、AssertJ、CLIProxyAPI OpenAI-compatible Images API、SSE。

---

## 一、冻结后的协议决策

### 1. 图片尺寸映射

GPT Image 2 和 GPT Image 1.5 都只开放 Low、Medium、High 三档，并统一使用上游支持的三种尺寸：

| 画幅 | 上游 `size` | 宽 | 高 |
| --- | --- | ---: | ---: |
| `SQUARE` | `1024x1024` | 1024 | 1024 |
| `LANDSCAPE` | `1536x1024` | 1536 | 1024 |
| `PORTRAIT` | `1024x1536` | 1024 | 1536 |

不再发送 `1280x720`、`2048x1152`、`2560x1440`、`3840x2160` 等自定义尺寸。档位不再通过分辨率区分，只通过已有质量映射区分。

### 2. 质量映射

| 模型 | 产品档位 | 上游 `quality` |
| --- | --- | --- |
| GPT Image 2 | Low | `low` |
| GPT Image 2 | Medium | `medium` |
| GPT Image 2 | High | `high` |
| GPT Image 1.5 | Low | `low` |
| GPT Image 1.5 | Medium | `medium` |
| GPT Image 1.5 | High | `high` |

直接 Images API 不发送 `reasoning.effort`。为避免破坏已经写入 Rabbit/Redis 的 Generation 输入快照，第一版保留 Java 领域对象里的 `reasoningEffort` 字段，但请求工厂忽略它。两个图片模型的能力列表都只返回 `[1, 2, 3]`，因此前端只展示 Low、Medium、High，并拒绝提交 Extra High 或 Ultra。

### 3. 输出与流式行为

固定发送：

```json
{
  "n": 1,
  "stream": true,
  "partial_images": 3,
  "output_format": "webp",
  "output_compression": 90
}
```

- 最多三张中间图只发到前端内存预览，不上传 OSS、不入库。
- `image_generation.completed` 的最终图先发到前端，再进入现有 OSS 最终化流程。
- 只有最终 OSS URL 进入现有附件 JSON/数据库；不持久化 Base64。
- OSS 最终化失败沿用当前 `IMAGE_OSS_PERSISTENCE_DROPPED` 行为，不重新调用模型。

### 4. usage 映射

```text
AiConversationUsage.promptTokens        = usage.input_tokens
AiConversationUsage.cachedPromptTokens  = 0
AiConversationUsage.completionTokens    = usage.output_tokens
AiConversationUsage.reasoningTokens     = 0
```

`input_tokens_details.text_tokens` 和 `image_tokens` 暂不新增数据库字段。现有计费策略继续接收汇总后的 `AiConversationUsage`；本任务不改变结算表结构和事务边界。

## 二、文件结构与影响边界

### 必须修改的生产文件

- `ai-temperate-web/src/main/resources/application.yml`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/config/AiConversationImageGenerationProperties.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImageProfileServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/AiConversationImageProfile.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageRequestFactory.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageEventMapper.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/ResponsesImageGenerationStreamingStrategy.java`

### 必须修改的测试文件

- `ai-temperate-web/src/test/resources/application-test.yml`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImageProfileServiceImplTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageRequestFactoryTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageEventMapperTest.java`

### 明确不修改

- `fornted/**`：继续消费内部 `image-preview` 和 `completed.attachments`。
- `AiConversationGenerationWorkerImpl`：继续消费内部 `Image(PARTIAL/FINAL)` 和 `Chunk(usage)`。
- `AiConversationImagePreviewBrokerImpl`：继续发布现有前端事件。
- OSS 最终化、附件 Codec、Billing Consumer、Mapper、SQL：内部合同不变。
- `OpenAiResponsesSseDecoder`：它本质是通用 SSE 拆帧器，Images 流可以复用。
- 数据库表、字段、索引：不变。

第一批不重命名带 `Responses` 的类和协议枚举，避免扩大影响面；只更新其实现和 JavaDoc。后续若要清理命名，单独执行纯重构任务。

---

### Task 1: 将所有图片档位收敛到合法上游尺寸

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImageProfileServiceImplTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImageProfileServiceImpl.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/AiConversationImageProfile.java`

- [ ] **Step 1: 先更新档位契约测试**

将 GPT Image 2 三档每个画幅的预期尺寸都改为：

```java
List<String> supportedSizes = List.of(
        "1024x1024",
        "1536x1024",
        "1024x1536");

assertTier("gpt-image-2", 1, AiConversationImageQuality.LOW,
        supportedSizes, AiConversationReasoningEffort.LOW);
assertTier("gpt-image-2", 2, AiConversationImageQuality.MEDIUM,
        supportedSizes, AiConversationReasoningEffort.LOW);
assertTier("gpt-image-2", 3, AiConversationImageQuality.HIGH,
        supportedSizes, AiConversationReasoningEffort.MEDIUM);
```

GPT Image 1.5 的三档继续断言同一组尺寸。为两个模型分别增加 Extra High 和 Ultra 拒绝测试，并断言 `supportedLevels()` 都只返回 `(short) 1, (short) 2, (short) 3`。测试 JavaDoc 改为说明 `reasoningEffort` 只是冻结的产品元数据，不再声称它发送到 Responses 外层。

- [ ] **Step 2: 第二阶段授权后执行单测并确认旧实现失败**

Run:

```powershell
mvn -pl ai-temperate-service -am -Dtest=AiConversationImageProfileServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: GPT Image 2 的旧自定义尺寸断言失败；不得连接数据库、Redis、RabbitMQ、OSS 或 CLIProxyAPI。

- [ ] **Step 3: 修改档位服务尺寸**

在 `image2()` 和 `image15()` 中，对每个 tier 都传入相同的标准尺寸：

```java
private static final int SQUARE_WIDTH = 1024;
private static final int SQUARE_HEIGHT = 1024;
private static final int LANDSCAPE_WIDTH = 1536;
private static final int LANDSCAPE_HEIGHT = 1024;
private static final int PORTRAIT_WIDTH = 1024;
private static final int PORTRAIT_HEIGHT = 1536;
```

每次构造统一使用：

```java
tier(
        quality,
        SQUARE_WIDTH,
        SQUARE_HEIGHT,
        LANDSCAPE_WIDTH,
        LANDSCAPE_HEIGHT,
        PORTRAIT_WIDTH,
        PORTRAIT_HEIGHT,
        reasoningEffort)
```

将 GPT Image 2 的 `levels` 从五档收敛为三档：

```java
List.of((short) 1, (short) 2, (short) 3)
```

删除 `image2()` 中 Extra High 与 Ultra 的 profile 注册。`required()` 对 GPT Image 2 和 GPT Image 1.5 的 4/5 档都返回受控 `AI_REQUEST_INVALID`，错误消息统一说明图片生成只支持 Low、Medium、High 三档。

- [ ] **Step 4: 更新领域 JavaDoc**

`AiConversationImageProfile` 的说明改为：质量和尺寸是上游参数，`reasoningEffort` 是为兼容异步快照保留的产品档位元数据，不发送给 Images API。

- [ ] **Step 5: 第二阶段授权后重新执行档位单测**

Run: 与 Step 2 相同。

Expected: `AiConversationImageProfileServiceImplTest` 全部通过。

### Task 2: 将图片端点配置改为 Images Generation

**Files:**
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Modify: `ai-temperate-web/src/test/resources/application-test.yml`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/config/AiConversationImageGenerationProperties.java`

- [ ] **Step 1: 重命名配置属性**

将 record 组件从：

```java
@NotBlank String responsesPath
```

改为：

```java
@NotBlank String generationsPath
```

校验方法改为 `isGenerationsPathValid()`，保持现有相对路径安全限制：必须以单个 `/` 开头，禁止 `//`、`..`、查询串和 Fragment。

- [ ] **Step 2: 修改正式 YAML**

只修改 `app.ai-conversation.image-generation`，不要修改联网搜索的 `responses-path`：

```yaml
# 图片生成通过 CLIProxyAPI Images Generation 端点请求单张最终图和最多三张中间预览。
generations-path: ${AI_CONVERSATION_IMAGE_GENERATIONS_PATH:/v1/images/generations}
```

同时更新 `enabled` 上方的中文注释，删除“Responses 图片生成”的错误描述。严格保持“一行中文注释，下一行配置”。

- [ ] **Step 3: 修改测试 YAML**

在测试配置的 `image-generation` 组使用：

```yaml
# 测试绑定正式 Images Generation 相对路径，但不会连接真实上游。
generations-path: /v1/images/generations
```

- [ ] **Step 4: 第二阶段授权后运行 Spring 配置绑定测试**

先定位现有最小 Spring 上下文测试；若没有专用测试，运行：

```powershell
mvn -pl ai-temperate-web -am -Dtest=AiConversationResponseControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 配置解析与 Bean 创建成功，测试不连接生产基础设施。

### Task 3: 把请求工厂改成 `/v1/images/generations` 请求体

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageRequestFactoryTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageRequestFactory.java`

- [ ] **Step 1: 先把请求体测试改成 Images 合同**

测试应断言：

```java
assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
assertThat(body.path("prompt").asText()).isEqualTo("draw a quiet lake");
assertThat(body.path("n").asInt()).isEqualTo(1);
assertThat(body.path("stream").asBoolean()).isTrue();
assertThat(body.path("partial_images").asInt()).isEqualTo(3);
assertThat(body.path("quality").asText()).isEqualTo("high");
assertThat(body.path("size").asText()).isEqualTo("1536x1024");
assertThat(body.path("output_format").asText()).isEqualTo("webp");
assertThat(body.path("output_compression").asInt()).isEqualTo(90);
```

并明确断言 Responses 专属字段不存在：

```java
assertThat(body.has("input")).isFalse();
assertThat(body.has("instructions")).isFalse();
assertThat(body.has("tools")).isFalse();
assertThat(body.has("tool_choice")).isFalse();
assertThat(body.has("reasoning")).isFalse();
assertThat(body.has("store")).isFalse();
assertThat(body.has("max_output_tokens")).isFalse();
```

测试输入尺寸改成 `1536x1024`。

- [ ] **Step 2: 第二阶段授权后运行测试确认旧工厂失败**

```powershell
mvn -pl ai-temperate-service -am -Dtest=OpenAiResponsesImageRequestFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 旧工厂仍产生 `tools/input/reasoning`，测试失败。

- [ ] **Step 3: 实现最小 Images 请求体**

请求工厂只创建：

```java
ObjectNode root = objectMapper.createObjectNode();
root.put("model", request.modelRequest().modelName());
root.put("prompt", prompt);
root.put("n", 1);
root.put("quality", image.quality().upstreamValue());
root.put("size", image.size());
root.put("output_format", image.outputFormat());
root.put("output_compression", image.outputCompression());
root.put("stream", true);
root.put("partial_images", image.partialImages());
return root;
```

第一版只发送当前用户文字 `currentInput().text()`，不把通用文本助手 system prompt 拼进图片 prompt，不上传用户附件，也不记录请求正文或 Base64。

- [ ] **Step 4: 更新工厂 JavaDoc**

说明该类构造 CLIProxyAPI Images Generation JSON，并明确不负责网络调用、附件编辑和持久化。

- [ ] **Step 5: 第二阶段授权后重新运行工厂测试**

Run: 与 Step 2 相同。

Expected: 测试全部通过，JSON 中不存在任何 Responses 工具字段。

### Task 4: 把 Images SSE 转成项目内部预览、最终图和 usage

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageEventMapperTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageEventMapper.java`

- [ ] **Step 1: 编写 partial event 契约**

测试事件改为：

```json
{
  "type": "image_generation.partial_image",
  "partial_image_index": 1,
  "b64_json": "<BASE64>",
  "size": "1536x1024",
  "quality": "high",
  "output_format": "webp"
}
```

继续断言 Mapper 输出一个 `Image(PARTIAL)`、index 为 1、字节与 Base64 解码结果一致。

- [ ] **Step 2: 编写 completed event 与 usage 契约**

测试事件改为：

```json
{
  "type": "image_generation.completed",
  "b64_json": "<BASE64>",
  "usage": {
    "total_tokens": 34,
    "input_tokens": 13,
    "output_tokens": 21,
    "input_tokens_details": {
      "text_tokens": 13,
      "image_tokens": 0
    }
  },
  "size": "1536x1024",
  "quality": "high",
  "output_format": "webp"
}
```

断言输出顺序固定为：

```text
Image(FINAL)
Chunk(usage)
```

并断言：

```java
assertThat(usage.promptTokens()).isEqualTo(13);
assertThat(usage.cachedPromptTokens()).isZero();
assertThat(usage.completionTokens()).isEqualTo(21);
assertThat(usage.reasoningTokens()).isZero();
```

- [ ] **Step 3: 增加错误边界测试**

覆盖以下输入：

```text
partial_image_index < 0
partial_image_index >= configured partialImages
缺少 b64_json
非法 Base64
Base64 解码后超过 maximumDecodedBytes
type=error
[DONE]
```

其中非法或超限图片必须抛受控协议异常；`error` 映射为内部 Failure；`[DONE]` 不重复产生最终图。

- [ ] **Step 4: 第二阶段授权后运行 Mapper 测试确认旧实现失败**

```powershell
mvn -pl ai-temperate-service -am -Dtest=OpenAiResponsesImageEventMapperTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 旧 Mapper 不识别 `image_generation.*` 且读取错误字段，测试失败。

- [ ] **Step 5: 修改事件分派**

```java
return switch (type) {
    case "image_generation.partial_image" -> partial(root, options);
    case "image_generation.completed" -> completed(root, options);
    case "error" -> List.of(new AiConversationModelEvent.Failure(
            "UPSTREAM_IMAGE_RESPONSE_FAILED"));
    default -> List.of();
};
```

- [ ] **Step 6: 修改图片字段读取**

partial 与 completed 都从顶层读取：

```java
byte[] bytes = decode(text(root, "b64_json"));
```

最终图继续使用 `AiConversationGeneratedImagePhase.FINAL`；如果上游没有图片 ID，沿用本地稳定回退值 `image-<index>`，不依赖上游临时 URL。

- [ ] **Step 7: 修改 usage 映射**

```java
long prompt = number(usage, "input_tokens");
long completion = number(usage, "output_tokens");
AiConversationUsage mapped = new AiConversationUsage(
        prompt,
        0L,
        completion,
        0L);
```

`upstreamRequestId` 只在 completed 事件确实提供 `id` 时保存；没有则允许为 `null`，禁止伪造 OpenAI request ID。

- [ ] **Step 8: 更新 Mapper JavaDoc 并重新执行单测**

Run: 与 Step 4 相同。

Expected: partial、completed、usage、错误边界测试全部通过。

### Task 5: 将流式策略切换到 Images 端点

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/ResponsesImageGenerationStreamingStrategy.java`

- [ ] **Step 1: 修改 URI 属性访问**

```java
.uri(imageProperties.generationsPath())
```

继续使用：

```java
.contentType(MediaType.APPLICATION_JSON)
.accept(MediaType.TEXT_EVENT_STREAM)
```

- [ ] **Step 2: 保留现有安全与可靠性边界**

不得改变：

```text
image-generation.enabled 检查
webSearchMode == OFF 检查
总流时限
单事件最大字符数
单图片最大解码字节数
DataBuffer release
非 2xx 分类
非 text/event-stream 分类
上游 Failure 转协议失败
不自动重新调用模型
```

- [ ] **Step 3: 更新策略 JavaDoc**

说明它调用 CLIProxyAPI `/v1/images/generations`，并把 SSE 映射为项目内部事件；删除“不会调用 Images 专用端点”的旧说明。

- [ ] **Step 4: 编译级检查范围**

由于 `responsesPath()` 改名为 `generationsPath()`，使用 `rg` 确认图片配置不再引用旧属性，同时联网搜索仍保留自己的 Responses 属性：

```powershell
rg -n "imageProperties\.responsesPath|AI_CONVERSATION_IMAGE_RESPONSES_PATH" ai-temperate-service ai-temperate-web
rg -n "webSearchProperties\.responsesPath" ai-temperate-service
```

Expected: 第一条没有结果；第二条仍命中 `ResponsesWebSearchStreamingStrategy`。

### Task 6: 验证内部合同保持不变

**Files:**
- No production source changes expected.
- Verify: `fornted/common/aichat/ai-conversation-stream.js`
- Verify: `fornted/common/aichat/ai-conversation-image-generation.js`
- Verify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/worker/impl/AiConversationGenerationWorkerImpl.java`
- Verify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImagePreviewBrokerImpl.java`
- Verify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/billing/impl/AiConversationGenerationBillingConsumerImpl.java`

- [ ] **Step 1: 静态确认前端合同**

确认前端仍只处理：

```text
event.type === 'image-preview'
event.data.base64
event.data.phase
completed.attachments
```

不得把 `image_generation.partial_image` 暴露给浏览器；上游事件只能由后端 Mapper 消化。

- [ ] **Step 2: 静态确认最终化合同**

确认 Worker 仍收到：

```text
AiConversationModelEvent.Image(PARTIAL)
AiConversationModelEvent.Image(FINAL)
AiConversationModelEvent.Chunk(AiConversationUsage)
```

只有 FINAL 进入 OSS；PARTIAL 只进入 Preview Broker。

- [ ] **Step 3: 静态确认计费合同**

确认 Billing Consumer 继续读取现有四字段 `AiConversationUsage`，不新增数据库字段，不把 `total_tokens` 重复计费。

### Task 7: 第二阶段定向验证

**Prerequisite:** 用户必须在代码交付后明确批准本次测试范围。测试必须使用本地测试配置，不连接生产 PostgreSQL、Redis、RabbitMQ、OSS 或生产 CLIProxyAPI。

- [ ] **Step 1: 运行三个纯单元测试**

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiConversationImageProfileServiceImplTest,OpenAiResponsesImageRequestFactoryTest,OpenAiResponsesImageEventMapperTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 三组测试通过，无外部 I/O。

- [ ] **Step 2: 运行 Web 配置/Controller 定向测试**

```powershell
mvn -pl ai-temperate-web -am -Dtest=AiConversationResponseControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Spring 配置绑定成功，图片 Generation 开关与请求校验测试通过。

- [ ] **Step 3: 仅在用户再次批准本地上游联调后执行 CLIProxyAPI 冒烟请求**

联调范围必须先说明会调用本机 CLIProxyAPI 并可能实际消耗模型额度。请求只使用虚构提示词和非生产身份：

```http
POST /v1/images/generations
Accept: text/event-stream
Content-Type: application/json
```

验收证据：

```text
HTTP 2xx
Content-Type 为 text/event-stream
至少收到 image_generation.completed
completed 包含可解码 b64_json
usage.input_tokens 与 usage.output_tokens 为非负数
若收到 partial_image，索引在 0..2 范围
最终图上传 OSS 后 completed.attachments 只包含 URL，不包含 Base64
```

### Task 8: 交付检查

- [ ] **Step 1: 检查改动范围**

```powershell
git diff -- ai-temperate-web/src/main/resources/application.yml ai-temperate-web/src/test/resources/application-test.yml ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/config/AiConversationImageGenerationProperties.java ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl
```

确认没有覆盖工作区中与本任务无关的用户改动。

- [ ] **Step 2: 检查禁止项**

```powershell
rg -n "response\.image_generation_call\.partial_image|partial_image_b64|AI_CONVERSATION_IMAGE_RESPONSES_PATH" ai-temperate-service ai-temperate-web
rg -n "\"tools\"|\"tool_choice\"|\"reasoning\"" ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiResponsesImageRequestFactory.java
```

Expected: 均无结果。

- [ ] **Step 3: 交付报告**

报告必须区分：

```text
已修改代码
已编写但未执行的测试
已执行并通过的测试
失败或跳过的测试
是否执行真实 CLIProxyAPI/OSS 联调
```

不得在没有新证据时声称构建成功或功能已验证。

---

## 三、验收标准

1. GPT Image 2 和 GPT Image 1.5 都请求 `/v1/images/generations`。
2. 请求体不存在 Responses `tools/tool_choice/reasoning/input` 字段。
3. 两个模型都只发送三种合法画幅尺寸。
4. `partial_images=3`，中间图可在前端实时替换，但不持久化。
5. `image_generation.completed` 的最终 Base64 被解码并进入现有 OSS 最终化。
6. 数据库只保存最终 OSS URL，不保存 Base64。
7. usage 正确映射为输入/输出 token，缓存和推理 token 固定为零。
8. 前端请求与事件合同不变，前端文件零修改。
9. 不新增表、字段、索引或迁移脚本。
10. 上游失败不重新调用模型；现有退款和 OSS 丢弃策略保持不变。
