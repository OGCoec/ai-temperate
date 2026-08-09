# AI Temperate API Gateway

该 Worker 为 `niko000o.site` 和 `admin.niko000o.site` 提供同源 `/api` 入口，同时为普通
H5 提供精确的 `/ws/voice` WebSocket 入口，并把允许的请求原路径转发到
`https://api.niko000o.site`。

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

Spring Boot 首次部署使用 `EDGE_PROXY_MODE=OPTIONAL`；两个前端和正式 Routes 完成切换、
旧父域 Cookie 已清理后改为 `EDGE_PROXY_MODE=REQUIRED`。生产环境不得启用
`workers.dev`，不得把 Secret 写入 `wrangler.jsonc`、日志或 Git。

邮件检查实时接口固定匹配
`/api/admin/mail-inspection/jobs/{22字符jobId}/events`。Worker 不读取完整响应体，
直接流式转发 Origin 的 `text/event-stream`，透传 `Last-Event-ID`、`X-Trace-Id`，
并把客户端取消信号传播到 Origin。响应固定禁止浏览器和 CDN 缓存及转换。

语音 WebSocket 只允许根域名的精确 `/ws/voice` 路径。Worker 校验 GET Upgrade 后，删除
Cookie、Authorization 和客户端伪造的边缘头，为握手生成同一套 v2 HMAC，再向
`https://api.niko000o.site/ws/voice` 发起上游 Upgrade。上游必须返回不携带 Set-Cookie 的
101 WebSocket 响应；Worker 直接返回运行时 WebSocket 对象，不读取音频帧或转写内容。
生产发布顺序固定为 Worker、H5、后端 `/ws/voice` REQUIRED 验签收口，避免旧 H5 在切换前
被直接连接禁令阻断。Android 无 Origin 直连仍由连接后的单次语音票据保护。

`SSE_ROUTE_LOG_SAMPLE_RATE` 只控制低比例入口诊断日志。日志仅包含固定路由模板、
HTTP 状态和有界 `CF-Ray`，禁止记录真实 Job ID、Cookie、Authorization 或请求头。

第一阶段只交付源码，不自动执行 `npm install`、`npm test` 或 `wrangler deploy`。
