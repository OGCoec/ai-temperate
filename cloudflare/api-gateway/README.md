# AI Temperate API Gateway

该 Worker 为 `niko000o.site` 上的 H5 与 Android 提供统一 `/api` 和精确 `/ws/voice`
入口，并为 `admin.niko000o.site` 提供隔离的管理员 `/api/admin/**` 入口。所有允许的请求
保持原路径回源到 `https://api.niko000o.site`；`api.` 只作为 Worker 回源，不再是生产客户端
的公开直连入口。

部署前必须把同一份至少 32 字节随机密钥的规范 Base64 值分别保存为：

```text
Cloudflare Worker Secret: EDGE_PROXY_HMAC_SECRET_BASE64
Spring Boot 环境变量: EDGE_PROXY_HMAC_SECRET_BASE64
```

Spring Boot 生产切换值固定为：

```text
CORS_ALLOWED_ORIGINS=https://niko000o.site
ADMIN_ALLOWED_ORIGINS=https://admin.niko000o.site
EDGE_PROXY_MODE=REQUIRED
EDGE_PROXY_MAX_CLOCK_SKEW=30s
```

正式切换时必须删除而不是保留空值定义：

```text
AUTH_COOKIE_DOMAIN
ADMIN_COOKIE_DOMAIN
ADMIN_CSRF_COOKIE_DOMAIN
```

Worker 的普通变量由 `wrangler.jsonc` 固定为
`API_UPSTREAM_ORIGIN=https://api.niko000o.site`，共享密钥只能通过 Secret 注入，不能改成普通
变量。

v2 签名同时绑定 `X-AIT-Edge-Host`、`X-AIT-Edge-Timestamp`、`X-AIT-Edge-Ray`、
`X-AIT-Edge-IP`、`X-AIT-Edge-Country`、`X-AIT-Edge-ASN`、纬度、经度以及
`X-AIT-Edge-Signature`。原始 Cloudflare Ray 复制到专用头后再参与 HMAC，避免子请求阶段
平台调整 `CF-Ray` 导致误判。客户端自带的代理头和 `X-AIT-Edge-*` 会先被删除，后端只使用
验签成功后写入请求属性的网络上下文。

网络风险启用时，Spring Boot 还必须配置独立的：

```text
NETWORK_RISK_MODE=OBSERVE
NETWORK_RISK_HMAC_SECRET_BASE64=<至少32字节随机值的规范Base64>
IP2LOCATION_API_KEY_ENCRYPTION_KEY_BASE64=<另一份至少32字节随机值的规范Base64>
```

这两个网络风险 Secret 不写入 Worker；Worker 与后端共享的仍只有
`EDGE_PROXY_HMAC_SECRET_BASE64`。

Spring Boot 首次部署使用 `EDGE_PROXY_MODE=OPTIONAL`；Worker、H5 与 Android 主域名入口
完成验证后立即改为 `EDGE_PROXY_MODE=REQUIRED`。REQUIRED 对 `/api`、`/api/**` 和精确
`/ws/voice` 全部要求有效 Worker HMAC，不为无 Origin Android 保留直连例外。生产环境不得启用
`workers.dev`，不得把 Secret 写入 `wrangler.jsonc`、日志或 Git。

Worker 把请求运输分成 `H5_BROWSER`、`ANDROID_NATIVE` 与
`ANDROID_WEBVIEW_DOCUMENT`。Android 原生 API 只有在 `X-Client-Platform: ANDROID`、
无 Origin 且没有任何 `Sec-Fetch-*` 头时才使用原生运输。受控 WebView 运输只允许根域
`GET /api/auth/turnstile/page`，并要求合法的 challenge、action、PreAuth 与设备安装 ID；
出现 Fetch Metadata 时必须是顶层 `navigate`、`document` 导航，Site 只接受缺失、`none`
或 `same-origin`，User 只接受缺失或 `?1`，Origin 只接受缺失或精确根域。该兼容分支
不依赖 User-Agent、`wv`、`X-Requested-With` 或具体 WebView 版本。

两类 Android 运输都不要求 H5 Cookie Scope，回源前删除 Cookie、Origin、Referer 与
Fetch Metadata，同时保留显式 Token、PreAuth、CSRF 和设备安装 ID 头，并统一规范化为
`X-Client-Platform: ANDROID`。Android 上游响应出现任何 Set-Cookie 时返回
`502 EDGE_ANDROID_COOKIE_POLICY_VIOLATION`。除精确 WebView 文档导航外，任何携带 Origin
或 Fetch Metadata 的 Android 请求仍返回 `403 EDGE_CLIENT_TRANSPORT_INVALID`；禁止全局
放宽该规则。缺少或未知平台按更严格的 H5 Cookie Scope 策略处理，不能降级为 Android。

邮件检查实时接口固定匹配
`/api/admin/mail-inspection/jobs/{22字符jobId}/events`。Worker 不读取完整响应体，
直接流式转发 Origin 的 `text/event-stream`，透传 `Last-Event-ID`、`X-Trace-Id`，
并把客户端取消信号传播到 Origin。响应固定禁止浏览器和 CDN 缓存及转换。

语音 WebSocket 只允许根域名的精确 `/ws/voice` 路径，并已严格切换到 Voice v2。H5 与
Android 都必须在 Upgrade 中提交且只提交两个 `Sec-WebSocket-Protocol` token：固定
`ait-voice-v2` 与 `ait-ticket.<43位Base64URL>`。Worker 校验 GET、Upgrade、子协议长度与结构，
删除 Cookie、Authorization 和客户端伪造的边缘头，保留合法子协议，并为握手生成同一套 v2
HMAC 后回源。Spring 在返回 101 前重新校验 Origin、平台、全局设备封禁、PreAuth 设备绑定、
WebRTC generation 与登录 Session；连接建立后不再接收认证 Ticket。

上游 101 必须带运行时 WebSocket 对象、只选择 `ait-voice-v2` 且不得设置 Set-Cookie。反射
`ait-ticket.*`、选择其他协议或缺少双向通道一律转换为 502。Spring 的
400/401/403/428/503 受控拒绝只保留状态并返回统一边缘错误体。Worker 不读取音频帧或转写
内容；H5 回源固定使用主域 Origin，Android 回源不带 Origin。生产发布必须在同一维护窗口完成
Spring、Worker、H5 与 Android，旧版首帧 Ticket 客户端不再兼容。

Android API 或 SSE 收到 `CF-Mitigated: challenge` 时，通过以下两个精确主域入口完成托管
验证和 Cookie 共享确认：

```text
GET /__edge/android-clearance
GET /__edge/android-clearance/status
```

两个入口都由 Worker 在边缘终止，不回源、不生成 HMAC，也不进入 H5 Cookie Scope。第一个
入口只有在请求已经携带非空 `cf_clearance` 时才返回完成页面，否则返回
`428 EDGE_CLEARANCE_REQUIRED`；第二个入口携带该 Cookie 时返回 204，否则返回相同 428。
Worker 页面不读取、输出或记录 Cookie 值。

Cloudflare 控制台必须为根域的第一个精确入口配置 `Managed Challenge`：

```text
http.host eq "niko000o.site"
and http.request.method eq "GET"
and http.request.uri.path eq "/__edge/android-clearance"
```

不得把规则扩大到 `/api/**`、`/__edge/*` 或 `/ws/**`，状态入口也不得强制 Challenge，否则
Android 无法区分 Cookie 未共享与重复挑战。现有 Bot Fight Mode 可以保留；Android通过系统
WebView 完成验证后，只把 `cf_clearance` 带到主域 Worker，Worker仍会在回源前删除所有 Android
Cookie。WebSocket错误事件没有可靠响应头，因此语音只依赖连接前的 Ticket HTTP请求完成恢复。

`SSE_ROUTE_LOG_SAMPLE_RATE` 只控制低比例入口诊断日志。日志仅包含固定路由模板、
HTTP 状态和有界 `CF-Ray`，禁止记录真实 Job ID、Cookie、Authorization 或请求头。

第一阶段只交付源码，不自动执行 `npm install`、`npm test` 或 `wrangler deploy`。
