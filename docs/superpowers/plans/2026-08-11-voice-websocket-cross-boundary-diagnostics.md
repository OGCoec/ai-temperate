# Voice WebSocket 跨边界结构化诊断日志实施计划

## 目标

在不改变 Cookie、鉴权、Ticket、Origin、WebSocket 子协议、响应状态及 Whisper 业务逻辑的前提下，建立 Cloudflare Worker、Servlet Filter、Edge HMAC、HandshakeInterceptor、WebSocketHandler 与本机 Whisper 之间可关联的结构化日志。

本计划只增加观察能力，不新增 AOP 切面。Servlet Upgrade 和 JDK WebSocket 异步回调必须在真实生命周期节点记录，不能依赖线程本地上下文跨异步线程传播。

## 不可改变的边界

- 诊断代码不得新增放行、拒绝、降级或重试判断。
- Worker 保持既有 Cookie、Authorization 删除策略。
- 保持现有 `EDGE_*` 响应状态、JSON 正文、101 响应及 WebSocket 对象。
- 不新增握手响应头，不包装请求或响应，不读取请求体。
- 不记录 Cookie、Ticket、Authorization、Edge HMAC、完整 IP、用户或设备标识、音频及识别正文。
- 异常只记录白名单化类型，不记录异常消息或堆栈。
- Java 诊断上下文必须显式传入异步调用，不使用 MDC 或 ThreadLocal 跨线程传递。
- 第一阶段只交付代码与测试代码，不执行测试、构建、部署或外部连接验证。

## 固定事件合同

| 边界 | 事件名 | 主要字段 |
| --- | --- | --- |
| Worker | `voice_ws_edge_summary` | `cfRay`、`transport`、Cookie 存在性、上游状态、WebSocket 存在性、子协议、Set-Cookie 数量、`edgeOutcome`、耗时 |
| Servlet Filter | `voice_ws_handshake_summary` | `traceId`、`edgeRay`、平台、Cookie 字节数、Upgrade、子协议形态、状态、Set-Cookie、结果、异常类型、耗时 |
| Edge HMAC | `voice_ws_edge_signature` | `traceId`、`edgeRay`、模式、Edge 头存在性、Ray 是否可信、结果 |
| Origin | `voice_ws_origin_classification` | `traceId`、`edgeRay`、平台、Origin 是否存在、是否允许、状态 |
| Ticket | `voice_ws_authorization` | `traceId`、`edgeRay`、协议形态、网络上下文、平台、授权结果、稳定错误码、状态、异常类型 |
| 公共 WebSocket | `voice_ws_connection_lifecycle` | `traceId`、`edgeRay`、阶段、关闭码、异常类型 |
| Whisper | `voice_whisper_upstream_lifecycle` | `traceId`、`edgeRay`、阶段、回环目标、端口、耗时、关闭码、异常类型 |

缺失值使用 `ABSENT`，格式错误使用 `INVALID`，不可获得的数字使用 `-1`。除受控枚举外，不得把外部输入作为日志字段值。

## 实施顺序

### 1. 最外层握手观察器

- 新增 `VoiceDiagnosticContext`，只保存随机 `traceId` 和白名单化 `edgeRay`。
- 新增仅匹配 `/ws/voice` 的 `VoiceWebSocketDiagnosticFilter`。
- Filter 在 Spring Security 和 WebSocket 握手链外记录请求形态、最终状态和耗时，并在结束后恢复 MDC。
- Filter 不写响应、不解析 Cookie、不记录子协议 Ticket。

### 2. 握手决策节点

- `EdgeProxySignatureFilter` 记录 Edge HMAC 的稳定结果，只有验签成功才标记 Ray 可信。
- `VoiceWebSocketOriginInterceptor` 只记录 Origin 是否存在以及 H5、Android、拒绝三类结果。
- `VoiceWebSocketSecurityHandshakeInterceptor` 为每个现有返回分支记录稳定错误码；成功后保留 principal 与诊断上下文。

### 3. 公共 WebSocket 生命周期

- `VoiceWebSocketHandler` 记录连接建立、首帧接受、传输错误和关闭。
- 每连接对象保存自己的诊断上下文；单例 Handler 不保存请求级可变状态。
- 不记录首帧 JSON、模型参数、语言、客户端消息或 Close Reason。

### 4. Cloudflare Worker 最终分支

- 每次语音握手最多输出一条 `voice_ws_edge_summary`。
- 记录客户端 Cookie 是否存在以及既有删除逻辑之后是否仍向上游转发 Cookie。
- 记录上游状态、`response.webSocket`、严格子协议匹配及 Set-Cookie 可读性和数量。
- 日志输出故障不得改变原始响应或 WebSocket 对象。

### 5. Java 到 Whisper 边界

- `VoiceTranscriptionGateway` 和 `WhisperUpstreamClient` 增加内部 `VoiceDiagnosticContext` 参数。
- `JdkWhisperUpstreamClient` 在 `buildAsync` 前、`onOpen`、连接失败、传输错误和关闭处记录生命周期。
- Gateway 在第一条 `session.start` 成功发送后记录事件。
- 连接 Future 与 `onError` 使用原子标志避免对同一次建连失败重复记录。

## 测试代码范围

- Filter：精确路径、101/拒绝/异常、Cookie 字节数、Ticket 脱敏、MDC 恢复、响应无副作用。
- Edge HMAC、Origin、Ticket：现有状态和调用次数不变，日志不包含敏感值。
- Handler：连接建立证据、上下文缺失、状态机与客户端消息不变。
- Worker：成功、授权失败、非允许状态、WebSocket/子协议缺失、Set-Cookie、fetch 异常和跨 Host Redirect。
- Whisper：成功连接、建连失败、首帧失败、建立后错误及关闭事件。

## 第二阶段验证门禁

只有用户再次明确授权后，才可以执行以下定向验证：

```powershell
mvn -pl ai-temperate-web -am "-Dtest=VoiceWebSocketDiagnosticFilterTest,EdgeProxySignatureFilterTest,VoiceWebSocketOriginInterceptorTest,VoiceWebSocketSecurityHandshakeInterceptorTest,VoiceWebSocketHandlerTest" -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl ai-temperate-service -am "-Dtest=VoiceTranscriptionGatewayImplTest,JdkWhisperUpstreamClientIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false test

Set-Location C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npm test
```

上述 Maven 单元测试不得连接生产基础设施；Whisper 集成测试只能使用测试内本地 TLS/WebSocket 服务。真实 H5/Android、Cloudflare 和 7896 联调还需要单独的部署与外部连接授权。

## 根因定位顺序

1. Worker 无法回源且 Spring 无 Filter 事件：故障在 Worker 到 Java 之间。
2. Filter 有事件但无 Edge HMAC 事件：检查 Filter 顺序或安全链注册。
3. Edge HMAC 为 `INVALID`：检查签名或受保护请求头。
4. Edge HMAC 通过但 Origin 拒绝：检查平台与 Origin 分类。
5. Origin 通过但 Ticket 拒绝：检查 Ticket、会话、设备或网络绑定。
6. Spring 最终 101 但 Worker 502：检查 Worker 的 WebSocket 对象、子协议或 Set-Cookie 策略。
7. 已建立公共连接但没有接受首帧：客户端没有发送合法 `session.start`。
8. 有 `CONNECT_STARTED` 但无 `WEBSOCKET_OPEN`：检查 7896、TLS 证书或 Whisper 握手。
9. 有 `WEBSOCKET_OPEN` 但无 `SESSION_START_SENT`：Java 到 Whisper 的首帧发送失败。
10. `SESSION_START_SENT` 之后失败：进入 Whisper 会话协议、容量或后续转写阶段排查。
