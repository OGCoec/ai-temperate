# 八家 AI 厂商官方 SVG 外链兼容 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不复制到 OSS 的前提下，让管理员能够登记 OpenAI、Anthropic、Google、xAI、DeepSeek、智谱、月之暗面和阿里巴巴通义千问的官方 SVG URL，并安全兼容受限 `<style>`、`class`、内联样式和内嵌栅格 `<image>`。

**Architecture:** 外部图片仍由现有远程验证器执行 HTTPS、DNS、SSRF、重定向、超时、Content-Type 和 2 MiB 限制；最终重定向主机再由八家厂商可信来源 Registry 选择 `STRICT` 或 `TRUSTED_OFFICIAL` SVG 档位。可信档位只扩大静态展示能力，不允许脚本、事件、外部子资源、HTML 或动画；验证通过后数据库仅保存最终 URL，`object_key = NULL`。

**Tech Stack:** Java 21、Spring Boot Configuration Properties、现有 Apache HttpClient 5/XML DOM/ImageIO 策略 Registry、`com.helger:ph-css:8.2.1`。

---

## 固定行为与边界

- 公共 API、请求体、响应体、数据库表和前端选择协议不变。
- `POST /api/admin/ai-model-icons/remote` 和通过 Merge Patch 切换外部 URL 时，始终不调用 OSS。
- 可信域名只启用兼容档位，不跳过 XML、CSS、图片解码和尺寸安全检查。
- 最终重定向 URL 的主机决定档位；可信主机跳转到非可信主机时自动回到 `STRICT`。
- 外链内容只在登记时验证；相同 URL 日后被厂商替换属于明确接受的外链风险，不增加定时扫描或代理缓存。
- 前端继续通过 `<image>`/`<img>` 加载 URL，禁止 `v-html`、`innerHTML`、`object` 和 `iframe` 内联 SVG。

### 默认厂商与主机

```text
OPENAI:    chatgpt.com, openai.com
ANTHROPIC: claude.ai, anthropic.com
GOOGLE:    gemini.google.com, www.gstatic.com
XAI:       grok.com, x.ai
DEEPSEEK:  deepseek.com
ZHIPU:     zhipuai.cn, chatglm.cn, bigmodel.cn
MOONSHOT:  kimi.com, moonshot.cn, moonshot.ai
ALIBABA_QWEN: qwen.ai
```

`images.ctfassets.net`、`is1-ssl.mzstatic.com` 等共享 CDN 不进入可信 SVG 默认列表；它们的 PNG/JPEG 等栅格图仍可按现有严格流程登记。

## Task 1：增加 CSS 解析依赖和可信来源配置

**Files:**
- Modify: `pom.xml`
- Modify: `ai-temperate-service/pom.xml`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconVendor.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconRemoteSvgProperties.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/config/AiModelInfrastructureConfiguration.java`
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Modify: `ai-temperate-web/src/test/resources/application-test.yml`
- Modify: `docs/third-party-licenses.md`

- [ ] **Step 1: 在父 POM 统一管理 ph-css 版本**

增加：

```xml
<ph-css.version>8.2.1</ph-css.version>
```

并在 `dependencyManagement` 中增加：

```xml
<dependency>
    <groupId>com.helger</groupId>
    <artifactId>ph-css</artifactId>
    <version>${ph-css.version}</version>
</dependency>
```

- [ ] **Step 2: 在 service 模块声明直接依赖**

```xml
<dependency>
    <groupId>com.helger</groupId>
    <artifactId>ph-css</artifactId>
</dependency>
```

- [ ] **Step 3: 建立稳定的八家厂商枚举**

```java
public enum AiModelIconVendor {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    XAI,
    DEEPSEEK,
    ZHIPU,
    MOONSHOT,
    ALIBABA_QWEN
}
```

- [ ] **Step 4: 建立配置属性**

使用 `@ConfigurationProperties(prefix = "ai-model-icon.remote-svg")` 和 `@Validated`。属性包含：

```java
public record AiModelIconRemoteSvgProperties(
        boolean trustedOfficialProfileEnabled,
        @NotNull TrustedHosts trustedHosts) {

    public record TrustedHosts(
            @NotEmpty List<@NotBlank String> openai,
            @NotEmpty List<@NotBlank String> anthropic,
            @NotEmpty List<@NotBlank String> google,
            @NotEmpty List<@NotBlank String> xai,
            @NotEmpty List<@NotBlank String> deepseek,
            @NotEmpty List<@NotBlank String> zhipu,
            @NotEmpty List<@NotBlank String> moonshot,
            @NotEmpty List<@NotBlank String> qwen) {
    }
}
```

在 `AiModelInfrastructureConfiguration` 中通过 `@EnableConfigurationProperties` 注册。

- [ ] **Step 5: 增加符合项目注释规范的 YAML**

```yaml
  # 外部 SVG 的官方厂商兼容策略，仅扩大静态展示白名单，不跳过内容安全验证。
  remote-svg:
    # 关闭后所有外部 SVG 立即回退到严格档位，作为无需改代码的安全回滚开关。
    trusted-official-profile-enabled: ${AI_MODEL_ICON_TRUSTED_OFFICIAL_SVG_ENABLED:true}
    # 八家厂商的官方主机列表；环境变量使用逗号分隔。
    trusted-hosts:
      # OpenAI 与 ChatGPT 官方主机。
      openai: ${AI_MODEL_ICON_TRUSTED_OPENAI_HOSTS:chatgpt.com,openai.com}
      # Anthropic 与 Claude 官方主机。
      anthropic: ${AI_MODEL_ICON_TRUSTED_ANTHROPIC_HOSTS:claude.ai,anthropic.com}
      # Google Gemini 页面和当前官方静态资源主机。
      google: ${AI_MODEL_ICON_TRUSTED_GOOGLE_HOSTS:gemini.google.com,www.gstatic.com}
      # xAI 与 Grok 官方主机。
      xai: ${AI_MODEL_ICON_TRUSTED_XAI_HOSTS:grok.com,x.ai}
      # DeepSeek 官方主机。
      deepseek: ${AI_MODEL_ICON_TRUSTED_DEEPSEEK_HOSTS:deepseek.com}
      # 智谱 AI、智谱清言和开放平台官方主机。
      zhipu: ${AI_MODEL_ICON_TRUSTED_ZHIPU_HOSTS:zhipuai.cn,chatglm.cn,bigmodel.cn}
      # 月之暗面与 Kimi 官方主机。
      moonshot: ${AI_MODEL_ICON_TRUSTED_MOONSHOT_HOSTS:kimi.com,moonshot.cn,moonshot.ai}
      # 通义千问官方主机；只信任 qwen.ai 及其点边界子域。
      qwen: ${AI_MODEL_ICON_TRUSTED_QWEN_HOSTS:qwen.ai}
```

- [ ] **Step 6: 更新第三方许可**

记录 `ph-css 8.2.1`、Apache-2.0、用途为 CSS AST 解析；不得描述为 SVG 安全沙箱，安全规则仍由项目白名单负责。

## Task 2：建立最终主机到 SVG 档位的 Registry

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/AiModelIconSvgPolicyProfile.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/AiModelIconImageValidationContext.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistry.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistryTest.java`

- [ ] **Step 1: 定义两个 SVG 档位**

```java
public enum AiModelIconSvgPolicyProfile {
    STRICT,
    TRUSTED_OFFICIAL
}
```

- [ ] **Step 2: 定义验证上下文**

```java
public record AiModelIconImageValidationContext(
        AiModelIconSvgPolicyProfile svgPolicyProfile,
        AiModelIconVendor vendor) {

    public static AiModelIconImageValidationContext strict() {
        return new AiModelIconImageValidationContext(
                AiModelIconSvgPolicyProfile.STRICT,
                null);
    }
}
```

厂商只用于稳定策略选择和低基数测试，不写入异常消息、URL、日志标签或数据库。

- [ ] **Step 3: 实现不可变 Registry**

构造时完成：

- 主机去空格、转小写、移除末尾点并通过 `IDN.toASCII` 规范化。
- 拒绝空值、端口、路径、通配符、IP 字面量和非法主机。
- 转换为不可变 `EnumMap<AiModelIconVendor, Set<String>>`。
- 同一配置主机重复归属不同厂商时启动失败。
- 关闭总开关时始终返回 `STRICT`。

匹配必须固定为：

```java
host.equals(configured)
        || host.endsWith("." + configured)
```

禁止只使用 `endsWith(configured)`，确保 `evil-chatgpt.com` 不会误匹配。

- [ ] **Step 4: 编写 Registry 测试**

覆盖：

- 八家根域和真实子域命中对应厂商。
- 大小写和末尾点规范化。
- `evil-chatgpt.com`、IP 地址、空白和非法 IDN 不命中。
- 重复归属启动失败。
- 总开关关闭后全部回到 `STRICT`。

## Task 3：把验证上下文传入现有格式策略

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/AiModelIconImageValidator.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/strategy/AiModelIconImageValidationStrategy.java`
- Modify: seven existing implementations under `image/strategy/impl`
- Modify: their existing unit tests

- [ ] **Step 1: 扩展策略接口**

```java
AiModelIconImageMetadata validate(
        byte[] bytes,
        String declaredContentType,
        AiModelIconImageValidationContext context);
```

- [ ] **Step 2: 保留严格默认入口**

```java
public AiModelIconImageMetadata validate(
        byte[] bytes,
        String declaredContentType) {
    return validate(bytes, declaredContentType,
            AiModelIconImageValidationContext.strict());
}
```

再增加显式上下文重载。这样本地上传、文件替换以及所有现有调用方默认保持 `STRICT`。

- [ ] **Step 3: 机械更新非 SVG 策略**

PNG、JPEG、WebP、GIF、ICO、AVIF 接收非空上下文但不依据厂商降低格式、解码、帧数、尺寸或累计像素限制。

- [ ] **Step 4: 更新 Registry 契约测试**

确认七种格式仍全部注册，重复与缺失格式继续启动失败，新增上下文不会改变非 SVG 验证结果。

## Task 4：实现受限 SVG CSS 策略

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/svg/AiModelIconSvgCssPolicy.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/svg/impl/AiModelIconSvgCssPolicyImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/image/svg/impl/AiModelIconSvgCssPolicyImplTest.java`

- [ ] **Step 1: 定义 CSS 策略接口**

接口分别验证完整 `<style>` 内容和单个 `style` 属性；任何解析警告、未知 AST 节点或不完整消费都抛出 `AI_MODEL_ICON_IMAGE_UNSAFE`。

- [ ] **Step 2: 使用 ph-css AST，不使用正则解析 CSS 文法**

完整样式表使用 `CSSReader.readFromString`，内联声明使用 `CSSReaderDeclarationList.readFromString`。解析采用严格错误处理，禁止浏览器容错后静默忽略危险片段。

- [ ] **Step 3: 固定允许的规则与选择器**

只允许：

- 普通 style rule。
- `@media (prefers-color-scheme: dark)`。
- `@media (prefers-color-scheme: light)`。
- `:root`、SVG白名单元素名、`.class`、`#id` 以及上述简单选择器的逗号列表。

拒绝组合器、属性选择器、其他伪类，以及 `@import`、`@font-face`、`@keyframes`、`@supports`、`@layer`、`@namespace`、`@page` 和未知 at-rule。

- [ ] **Step 4: 固定展示属性白名单**

允许：

```text
fill, fill-opacity, fill-rule,
stroke, stroke-width, stroke-linecap, stroke-linejoin,
stroke-miterlimit, stroke-dasharray, stroke-dashoffset, stroke-opacity,
opacity, color, display, visibility,
clip-path, clip-rule, mask,
stop-color, stop-opacity, transform
```

`url(...)` 只允许 `url(#local-id)`，且仅用于 `fill`、`stroke`、`clip-path` 和 `mask`。拒绝 `@import`、`javascript:`、`data:`、HTTP(S)、协议相对地址、反斜杠逃逸、CSS变量、`expression()`、外部字体和动画。

- [ ] **Step 5: 增加资源上限**

```text
全部 <style> 文本总计 <= 32 KiB
规则 <= 256
声明 <= 1024
单选择器 <= 256 字符
单属性值 <= 512 字符
```

- [ ] **Step 6: 编写 CSS 测试**

通过：

- ChatGPT 的 `:root { fill: #000 }`。
- `prefers-color-scheme: dark` 中的 `:root { fill: #fff }`。
- Gemini 的 `.st0 { fill: none }`。
- `clip-path:url(#clippath)` 和 `fill:url(#radial-gradient)`。

拒绝：

- `@import`、远程 `url()`、`data:`、`javascript:`。
- 动画、字体、未知 at-rule。
- 复杂选择器和所有资源上限越界。

## Task 5：验证 Gemini 风格的内嵌栅格 `<image>`

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/svg/AiModelIconEmbeddedRasterValidator.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/svg/impl/AiModelIconEmbeddedRasterValidatorImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/image/svg/impl/AiModelIconEmbeddedRasterValidatorImplTest.java`

- [ ] **Step 1: 只接受三种 Base64 Data URI**

```text
data:image/png;base64,...
data:image/jpeg;base64,...
data:image/webp;base64,...
```

MIME 大小写不敏感，但禁止额外参数、非 Base64 编码、SVG、HTML、GIF、ICO、AVIF 和未知媒体类型。

- [ ] **Step 2: 解码后复用现有格式策略**

实现类只组合 PNG、JPEG、WebP 三个已有策略，并使用 `STRICT` 上下文完成魔数、Content-Type、完整解码和尺寸验证；禁止通过主验证器回调 SVG，避免循环依赖和递归嵌套。

- [ ] **Step 3: 固定容器限制**

```text
每个 SVG 最多 8 个 <image>
全部内嵌图片解码字节合计 <= 1 MiB
每张宽高 <= 4096
全部内嵌图片累计像素 <= 4096 × 4096
只允许单帧栅格图片
```

`<image>` 的 `href`/`xlink:href` 必须是上述 Data URI；HTTP(S)、`//`、本地文件、普通相对路径和 `data:image/svg+xml` 全部拒绝。

- [ ] **Step 4: 编写内嵌图片测试**

覆盖三个合法格式、伪造 MIME、错误 Base64、损坏图片、超限字节、超限像素、超过 8 个条目、远程 href 和递归 SVG。

## Task 6：扩展 SVG DOM 策略

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/image/strategy/impl/SvgAiModelIconImageValidationStrategy.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/image/strategy/impl/SvgAiModelIconImageValidationStrategyTest.java`
- Add fixtures: `ai-temperate-service/src/test/resources/ai-model-icons/svg/`

- [ ] **Step 1: 保持严格档位完全兼容**

`STRICT` 继续拒绝 `<style>`、`class`、内联 `style`、`<image>` 和 Data URI；本地上传行为不变。

- [ ] **Step 2: 在可信档位增加最小元素与属性**

`TRUSTED_OFFICIAL` 额外允许：

```text
元素: style, image
属性: class, style
image属性: href/xlink:href, width, height, x, y,
           transform, preserveAspectRatio, opacity
```

`<style>` 只能交给 CSS Policy；`style` 属性也必须逐声明验证；`class` 和 `id` 使用安全 ASCII 标识符并限制长度。

- [ ] **Step 3: 校验引用闭包**

所有 `url(#id)`、`use href="#id"`、`clip-path` 和 `mask` 引用的 ID 必须在当前文档中唯一存在。禁止重复 ID、悬空引用以及对 `<style>`、`script` 等非图形节点的引用。

- [ ] **Step 4: 保留全局禁止项**

两个档位都拒绝：

- `script`、`foreignObject`、`iframe`、`audio`、`video`。
- 所有 `on*` 事件。
- SVG动画元素。
- 外部实体、DOCTYPE、XInclude。
- HTTP(S)子资源、外部字体、文件协议和协议相对URL。

- [ ] **Step 5: 使用本地最小样本测试**

不在测试中实时访问官网。加入：

- ChatGPT风格：`:root`、深浅色媒体查询和单个 path。
- Gemini风格：class、style、clipPath、radialGradient和一张很小的内嵌JPEG。
- 同一两个样本在 `TRUSTED_OFFICIAL` 通过、在 `STRICT` 拒绝。
- 危险SVG即使来自可信档位也继续拒绝。

## Task 7：按最终重定向主机选择档位

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/impl/AiModelIconRemoteImageValidatorImpl.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/remote/impl/AiModelIconRemoteImageValidatorImplTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/service/impl/AdminAiModelIconServiceImplTest.java`

- [ ] **Step 1: 注入可信来源 Registry**

远程验证器在拿到非重定向 2xx 响应后，以当前 `URI.getHost()` 解析上下文：

```java
AiModelIconImageValidationContext context =
        trustedOriginRegistry.resolve(current.getHost());
imageValidator.validate(result.bytes(), result.contentType(), context);
```

- [ ] **Step 2: 坚持最终主机原则**

- 每一跳继续执行现有 HTTPS、公网 DNS 和 SSRF 检查。
- 中间跳是可信厂商、最终跳不是时，使用 `STRICT`。
- 输入主机不是可信厂商、最终跳进入可信主机时，可以使用最终主机对应的可信档位。
- URL查询参数、路径和响应体不得参与厂商识别。

- [ ] **Step 3: 保持持久化与 OSS 行为**

确认现有 `createRemote` 和外链 Patch 仍然：

```text
icon_url = 最终验证通过的URL
object_key = NULL
OSS putObject调用次数 = 0
```

- [ ] **Step 4: 编写远程流程 Mock 测试**

覆盖八家主机、可信到非可信重定向、域名伪装、严格档位回退，以及 CSS/内嵌图片错误仍保留 `AI_MODEL_ICON_IMAGE_UNSAFE`。

## Task 8：错误诊断、文档和前端契约

**Files:**
- Modify: `docs/admin-ai-model-icon-api.md`
- Modify: `docs/operations/ai-model-icon-oss.md`
- Modify: `myuniappadmin/pages/ai-model-icons/index.test.cjs`
- Modify: relevant Java Advice tests

- [ ] **Step 1: 保持公共错误码稳定**

仍使用 `AI_MODEL_ICON_IMAGE_UNSAFE` 和 HTTP 400，避免API兼容性变化；开发诊断字段中的异常消息细分为：

```text
SVG element is not allowed by the selected policy.
SVG CSS rule is not allowed.
SVG CSS resource reference is not allowed.
SVG embedded image is invalid.
SVG embedded image exceeds safety limits.
```

消息不得包含完整URL、CSS原文、Base64、Object Key或响应内容。

- [ ] **Step 2: 更新API与运维文档**

明确：

- 八家厂商默认主机。
- 官方可信档位不是安全绕过。
- 外部URL不写OSS，`object_key = NULL`。
- 外链内容可在登记后发生变化。
- 共享CDN默认使用严格档位。
- 安全回滚环境变量。

- [ ] **Step 3: 保持前端协议不变**

前端仍提交 `iconName`、`iconUrl`、`description`，无需新增厂商字段。测试确认页面仍通过 `<image :src="icon.iconUrl">` 加载，并保持失败占位符和 `img-src https:` CSP。

## Task 9：分阶段验证

第一阶段只写代码、测试和文档，不运行测试、构建、SQL或真实外部URL请求。

获得明确授权后，先说明以下命令只使用本地 fixture/Mock，不连接 PostgreSQL、Redis、RabbitMQ、OSS 或外部网站，再运行：

```powershell
mvn -pl ai-temperate-service -Dtest=AiModelIconTrustedOriginRegistryTest,AiModelIconSvgCssPolicyImplTest,AiModelIconEmbeddedRasterValidatorImplTest,SvgAiModelIconImageValidationStrategyTest,AiModelIconRemoteImageValidatorImplTest,AdminAiModelIconServiceImplTest test
mvn -pl ai-temperate-web -Dtest=AdminAiModelIconExceptionHandlerTest,AiTemperateApplicationTest test
node myuniappadmin/pages/ai-model-icons/index.test.cjs
```

验收结果必须分别报告通过、失败、跳过和未执行项。

真实URL验收需要再次单独授权，建议依次验证：

```text
ChatGPT官方SVG：受限style与深浅色媒体查询
Gemini官方SVG：style/class与内嵌JPEG image
Claude、Grok、DeepSeek、智谱、Kimi：各选择一个当前官方图标URL
```

验收时确认数据库只出现外部 `icon_url`，`object_key` 为 `NULL`，OSS没有新增对象。

## 实施约束

- 不增加数据库迁移、缓存、清理任务、图片代理或定时重验证。
- 不根据文件后缀放行，仍以 Content-Type、SVG XML结构和真实内嵌图片内容为准。
- 不为八家厂商复制八套 SVG 解析器；厂商只负责可信最终主机归类。
- 不在循环中进行网络、Mapper、Redis或OSS逐条I/O。
- 所有新增或修改Java类型和关键安全分支使用符合项目规范的中文JavaDoc/注释。
- 所有YAML配置行前紧邻中文注释。
- 当前工作区大量未提交修改均视为用户所有；实施时只改本计划列出的文件，不覆盖或整理无关改动。
- 未获得提交授权前，不执行 `git add`、`git commit` 或推送。
