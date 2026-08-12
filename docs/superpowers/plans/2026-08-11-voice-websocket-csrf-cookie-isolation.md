# Voice WebSocket CSRF Cookie Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让公开语音 WebSocket 的成功 101 响应不再生成 `XSRF-TOKEN`，从而满足 Worker 的 WebSocket 零 Cookie 策略并消除该分支产生的 502。

**Architecture:** 在 Admin 与普通 Android/H5 安全链之间增加精确匹配 Voice 公共路径的专用 `SecurityFilterChain`。该链禁用 CSRF Filter，但继续安装 Edge HMAC Filter；Origin、一次性 Ticket、设备和网络上下文仍由现有握手拦截器验证。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Security、Spring WebSocket、JUnit 5、AssertJ、Cloudflare Workers、Node Test Runner。

---

## 不可改变的边界

- 不修改 Worker 对 WebSocket `Set-Cookie` 的拒绝策略。
- 不修改普通 H5 或 Admin 的 CSRF Cookie、Header、Domain、Path、Secure 或 SameSite。
- 不修改 `SpaCsrfTokenRequestHandler` 的主动 Token 解析逻辑。
- 不修改 Edge HMAC、Origin、Ticket、网络上下文、子协议或 Whisper 业务流程。
- 不新增 YAML、依赖、数据库、Redis、响应头或外部 API 变更。
- 不自动暂存、提交、部署或连接真实 7896。

## Task 1：先建立回归合同

**Files:**

- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/config/SecurityConfigurationTest.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/user/voice/VoiceConfigurationContractTest.java`

- [ ] 为 Voice 路径匹配器编写精确路径、Servlet Context Path、GET/POST 和路径绕过测试。
- [ ] 固定普通 H5 请求仍会解析 Deferred CSRF Token。
- [ ] 固定 Voice 链关闭 CSRF、保留 Edge Filter，并按 Admin 1、Voice 2、Android 3、H5 4 排序。
- [ ] 固定通用 CSRF Handler 不包含 Voice 路径特判。
- [ ] 固定 Worker 继续拒绝 WebSocket 响应 Cookie。

项目第一阶段禁止执行测试，因此 RED 证据必须留到用户再次授权的第二阶段生成。

## Task 2：隔离 Voice SecurityFilterChain

**File:**

- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/SecurityConfiguration.java`

- [ ] 注入 `VoiceProperties`，使用 `publicPath()`，不新增硬编码路径。
- [ ] 新增仅在 `app.voice.enabled=true` 时注册的 `@Order(2)` Voice 安全链。
- [ ] Voice 链复用无状态、CORS、统一异常入口和 `permitAll` 公共配置。
- [ ] Voice 链执行 `csrf(AbstractHttpConfigurer::disable)`，确保请求不进入 `CsrfFilter`。
- [ ] Voice 链继续在 CORS 前安装 `EdgeProxySignatureFilter`。
- [ ] 将 Android/H5 链分别调整为 Order 3/4。
- [ ] 路径匹配使用 Context Path 与配置路径精确拼接，不匹配尾斜杠、子路径和编码变体。

## Task 3：第一阶段静态交付

- [ ] 查看目标文件差异，确认 Worker、Admin CSRF、通用 CSRF Handler 和握手授权链没有被修改。
- [ ] 运行 `git diff --check`，只检查空白与补丁结构。
- [ ] 不运行 Maven、Node Test、构建、部署或真实链路验证。
- [ ] 保留现有脏工作区，不暂存、不提交。

## Task 4：第二阶段定向验证

只有再次获得用户明确授权后执行：

```powershell
mvn -pl ai-temperate-web -am "-Dtest=SecurityConfigurationTest,VoiceConfigurationContractTest,VoiceWebSocketDiagnosticFilterTest,EdgeProxySignatureFilterTest,VoiceWebSocketOriginInterceptorTest,VoiceWebSocketSecurityHandshakeInterceptorTest,VoiceWebSocketHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test

Set-Location C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npm test
```

验收要求为 0 failures、0 errors；普通 H5 CSRF、Worker Cookie 拒绝以及现有 HMAC/Origin/Ticket/Handler 测试必须继续通过。

## Task 5：真实链路验收

部署与真实 H5/Android 联调需要再次单独授权。成功证据必须同时满足：

```text
voice_ws_handshake_summary status=101 setCookiePresent=false
voice_ws_edge_summary upstreamStatus=101 setCookieCount=0
voice_ws_edge_summary edgeOutcome=EDGE_WEBSOCKET_UPGRADED
voice_ws_connection_lifecycle phase=SESSION_START_ACCEPTED
voice_whisper_upstream_lifecycle phase=CONNECT_STARTED
voice_whisper_upstream_lifecycle phase=WEBSOCKET_OPEN
voice_whisper_upstream_lifecycle phase=SESSION_START_SENT
```

如果 Spring 仍记录 `setCookiePresent=true`，优先检查 Voice matcher 与安全链顺序；只有出现 `CONNECT_STARTED` 但没有 `WEBSOCKET_OPEN` 时，才转向 7896/TLS/Whisper 排查。
