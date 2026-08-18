# OpenAI 风格宽松兼容矩阵

本项目用独立 Java 代码实现宽松兼容行为，不复制 new-api/AGPL 源码，也不宣称完整复制 OpenAI 平台。目标是让能够使用 OpenAI Chat Completions 或 Responses 模式的 Agent 共用稳定入口，同时保留认证、模型授权、Token 预扣与权威 Usage 结算边界。

```text
Base URL: https://niko000o.site/v1
GET  /models
POST /chat/completions
POST /responses
```

本次不增加 `/v1/messages` 或 Gemini 原生路由，也不执行 Chat Completions 与 Responses 之间的协议转换。模型必须显式声明当前入口对应的 `CHAT_COMPLETIONS` 或 `RESPONSES` 能力。

## 三种请求模式

| 模式 | 触发条件 | 未知 Body 字段 | 厂商 Adapter |
| --- | --- | --- | --- |
| `STRICT_DTO` | 兼容开关关闭 | 按旧 DTO 和旧验证规则处理 | 保持旧行为 |
| `LOOSE_NORMALIZED` | 兼容开关开启，模型不在透传列表 | 顶层及 Chat 消息未知字段静默丢弃 | 静默删除厂商不支持的可选字段 |
| `CONTROLLED_PASSTHROUGH` | 兼容开关开启，规范模型名命中透传列表 | 保留未知 Body 扩展 | 保留未知厂商扩展，仍删除已知不安全或不兼容字段 |

兼容开关作用于 OpenAI、xAI、Anthropic、Google 等全部已注册厂商，不按客户端名称或 User-Agent 写分支。关闭开关后，所有厂商立即回退到旧严格 DTO 路径。

受控透传只涉及 JSON Body。`Authorization`、Cookie、Host、转发链、内部签名和其他客户端 Header 永远不会因此透传。无论哪种兼容模式，网关都会强制覆盖规范模型名、流模式、有效 Token 上限、结算所需 Usage 和无状态字段。

## 共同硬边界

宽松不等于取消网关安全校验。下列条件仍在连接 8317 和额度预扣之前检查：

- 请求必须是合法 JSON 对象，序列化后不超过 1 MiB。
- `model` 必须存在，模型必须启用、属于 API Key 授权范围并声明当前路由能力。
- Chat 的 `messages` 必须是 1～256 项数组；Responses 的 `input` 必须存在，数组输入不得为空或超过 256 项。
- `stream` 缺省为 `false`，显式值必须是 JSON 布尔值。
- Token 字段必须是安全范围内的正整数，并受模型最大输出和上下文窗口限制。
- 工具数量和 UTF-8 总字节数继续受现有配置限制。
- 已识别的图片、音频和视频输入分别要求 `IMAGE_INPUT`、`AUDIO_INPUT` 和 `VIDEO_INPUT` 能力。
- `input_file` 因当前没有文件输入能力码而返回受控错误。

Java 不再验证完整消息角色状态机、工具调用引用关系或所有上游枚举，也不会为缺失的 `content` 等语义字段补空串。语义不完整但未破坏网关边界的请求会原样交给 8317/最终上游裁决。

## Chat Completions

普通宽松模式保留以下常用结构：

- 消息字段：`role`、`content`、`name`、`tool_calls`、`tool_call_id`、`function_call`、`reasoning_content`、`refusal`、`audio`。
- 生成字段：`max_completion_tokens`、`max_tokens`、`temperature`、`top_p`、`presence_penalty`、`frequency_penalty`、`stop`、`seed`、`n`。
- 模型与输出字段：`reasoning_effort`、`service_tier`、`verbosity`、`safety_identifier`、`user`、`logprobs`、`top_logprobs`、`prediction`。
- 缓存、工具与结构化输出：`prompt_cache_key`、`prompt_cache_options`、`tools`、`tool_choice`、`parallel_tool_calls`、旧版 `functions`、`function_call`、`response_format`。

`content`、工具参数、JSON Schema 和其他复杂已知值以 `JsonNode` 保留，不被收窄成字符串 DTO。`messages[].agent` 等消息方言字段在普通模式静默删除；模型位于透传列表时才会保留。

具体规范化规则：

- `stream` 省略时按 `false` 处理，JSON 与 SSE 均支持。
- `max_completion_tokens` 与 `max_tokens` 同时存在时，前者优先；出站统一写入有效 `max_completion_tokens`。
- `store` 始终覆盖为 `false`。
- 顶层 `modalities`、音频输出配置等本次未承诺的媒体输出字段删除。
- Function 工具保留；Web Search 只在模型声明 `WEB_SEARCH` 时保留；其他托管、custom 或 MCP 工具静默删除。
- 未识别的 content 块不由 Java 猜测语义，保留给最终上游处理。

非流式示例：

```json
{
  "model": "gpt-test",
  "messages": [{"role": "user", "content": "只返回 JSON"}],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "answer",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {"answer": {"type": "string"}},
        "required": ["answer"],
        "additionalProperties": false
      }
    }
  }
}
```

## Responses

普通宽松模式保留 `model`、`input`、`instructions`、`stream`、`max_output_tokens`、`reasoning`、Function 工具、`text`、采样字段、service tier、truncation、cache、user/safety、`include` 和 `client_metadata`。`input`、`reasoning`、`text`、工具内容及未知 input/content 块保持 JSON 结构。

具体规范化规则：

- `stream` 省略时返回 JSON；`stream=true` 返回原生 Responses SSE。
- `store` 始终覆盖为 `false`。
- `background`、`previous_response_id` 和 `conversation` 静默删除，不再由 Java 提前返回 400。
- Function 工具保留；Web Search 只在模型声明 `WEB_SEARCH` 时保留。
- File Search、Code Interpreter、MCP 和其他托管工具静默删除。
- 图片、音频、视频输入按模型能力门控；`input_file` 明确拒绝。

## 厂商 Adapter

公共规范化完成后，Registry 根据模型数据库中的厂商枚举选择唯一 Adapter，不根据 Codex、WorkBuddy、Claude Code 或其他客户端名称分支。

- OpenAI Adapter 保留公共层批准的全部已知字段。
- xAI、Anthropic 和 Google Adapter 对自己未声明支持的已知可选字段执行静默删除。
- 普通宽松模式不会让未知字段越过 Adapter。
- 受控透传模式可以保留未进入公共字段目录的厂商扩展，但不能恢复已经被状态、Token、工具或媒体安全规则删除的字段。
- 厂商、Adapter 和模型能力不一致时仍返回受控模型错误，不自动转换成另一种协议。

## 成功响应、错误与结算

请求兼容改造不重建成功正文：JSON 保留完整对象；SSE 保留原始 event 名称和 data JSON。旁路解析器只提取权威 Usage、缓存输入 Token、finish reason 和 Responses 终态。

Chat 非流式响应必须在本地结算成功后才建立 HTTP 200。流式 Chat 可以强制向上游请求最终 Usage；客户端没有请求 Usage 时，仅供结算的 Usage chunk 不向客户端输出。`n>1` 始终按上游总 Usage 结算。Usage 缺失、非法或终态不完整时继续执行现有协议错误、退款或恢复流程，禁止估算后收费。

合法 OpenAI error envelope 会保留原始 HTTP 状态和安全响应头。非 JSON、错误 Content-Type、包含内部 8317 信息或凭据的错误会转换为受控网关错误。允许的响应头仍仅限 `x-request-id`、`openai-*`、`x-ratelimit-*` 和 `retry-after`。

服务不会记录完整请求正文、工具参数、消息内容、Authorization、供应商凭据或完整模型名。规范化指标只包含 protocol、provider、payload mode、丢弃数量、上游结果和结算终态等低基数信息。

## 配置与部署

```text
# 默认 true；false 会让全部厂商恢复旧严格 DTO 路径。
API_KEY_OPENAI_COMPATIBILITY_ENABLED=true

# 逗号分隔、按规范模型名大小写不敏感匹配；默认空列表。
API_KEY_OPENAI_PASSTHROUGH_MODELS=gpt-test,gpt-5.6-terra
```

Cloudflare 仍只开放 `/v1/chat/completions`、`/v1/responses` 和 `/v1/models`，两个 create 路由根据上游实际 Content-Type 自适应转发 JSON 或 SSE，并保持无缓存、禁止 SSE 缓冲、Worker 验签和 API Key Header 安全边界。

本次不新增数据库表、公开路由、协议转换、持久资源或非 Token 计费逻辑。Java、Cloudflare 和本地假 8317 测试只有在用户明确批准后才执行；真实 8317、Codex、WorkBuddy 或其他 Agent 测试还需要单独批准，因为可能消耗模型额度。
