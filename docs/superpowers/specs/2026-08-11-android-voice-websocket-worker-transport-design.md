# Android Voice WebSocket Worker 运输兼容设计

## 问题

Android App-Plus 通过 `uni.connectSocket()` 发起 `/ws/voice` 握手时，会同时发送应用显式设置的 `X-Client-Platform: ANDROID` 和底层自动生成的浏览器运输元数据。Worker 当前把任意 Android `Origin` 或 `Sec-Fetch-*` 视为运输伪装并返回 `EDGE_CLIENT_TRANSPORT_INVALID`，因此请求在到达 Spring 前被拒绝。

## 决策

只为根域名精确 `/ws/voice` 建立 Android 专用运输例外。该例外不信任 Origin 或 Fetch Metadata，也不把它们作为授权条件；它依赖已经存在的精确路由、GET、WebSocket Upgrade、`ait-voice-v2`、一次性 Ticket 子协议、Edge HMAC、网络绑定和 Spring Ticket 授权。Worker 回源前继续删除 Cookie、Authorization、Origin、Referer 和全部 `Sec-Fetch-*`。

普通 Android API、Turnstile WebView、H5 Cookie Scope、跨 Host 路由和无效子协议保持现有规则。无需修改 `wrangler.toml`、环境变量、Spring 或 Whisper。

## 验收

- Android `/ws/voice` 携带 App-Plus Origin/Fetch Metadata 时可以进入上游握手。
- 回源请求仍标记为 Android，且不携带 Cookie、Authorization、Origin 或 Fetch Metadata。
- 普通 Android API 携带浏览器运输元数据时继续返回 `EDGE_CLIENT_TRANSPORT_INVALID`。
- H5 Voice、无效路径、非 GET、缺少 Upgrade 和无效子协议行为不变。

