# Android Cloudflare 托管挑战恢复设计

## 目标

Android 原生 HTTP 或 SSE 收到 Cloudflare 的 `CF-Mitigated: challenge` HTML 时，不再把页面
当作后端 JSON错误。APP打开全屏系统 WebView完成 Cloudflare托管验证，确认
`cf_clearance` 已共享给原生网络请求后，只重放被拦截请求一次。

H5现有Cookie、CSRF、Turnstile和网络流程保持不变；Java后端、语音WebSocket消息协议、数据库
和Redis均不变化。

## 边缘接口

Worker新增两个精确主域路由：

```text
GET /__edge/android-clearance
GET /__edge/android-clearance/status
```

两个路由都在边缘终止，不访问 `api.niko000o.site`。完成页只有在请求携带非空、长度有界且
不含控制字符的 `cf_clearance` 时返回独立HTML；页面通过 `ait-edge://verified` 通知Android。
状态接口携带同一Cookie时返回204，缺少时返回 `428 EDGE_CLEARANCE_REQUIRED`。响应统一
`no-store`，不返回或记录Cookie值。

Cloudflare控制台只对以下表达式执行 `Managed Challenge`：

```text
http.host eq "niko000o.site"
and http.request.method eq "GET"
and http.request.uri.path eq "/__edge/android-clearance"
```

状态接口不配置Managed Challenge。规则不得扩大到API、WebSocket或通配 `__edge` 路径。

## Android恢复协调器

协调器在同一时刻只允许一个验证WebView；并发失败请求等待同一Promise。WebView使用完整屏幕
打开主域验证入口，拦截精确 `ait-edge://verified` 后刷新Android CookieManager，并执行状态
探测。CookieManager只提取 `cf_clearance`，不把Access Token、Refresh Token、CSRF或其他
Cookie复制到请求，也不写入Storage、Pinia、Keystore、URL或日志。

取消、120秒超时、Cookie未共享和重复Challenge分别返回：

```text
EDGE_CHALLENGE_CANCELLED
EDGE_CHALLENGE_TIMEOUT
EDGE_CLEARANCE_NOT_SHARED
EDGE_CHALLENGE_REPEATED
```

第一次确认的 `EDGE_CHALLENGE` 完成验证后可重试一次；第二次仍被挑战时停止。用户再次点击
原业务按钮属于新的显式尝试，不进行后台无限循环。

## HTTP、SSE与WebSocket边界

PreAuth和共享HTTP客户端在每次Android请求前从CookieManager临时取得单个
`cf_clearance`。Worker根据Android运输规则在回源前删除Cookie，因此后端仍只接收显式Token
协议。Cloudflare Challenge在Origin之前终止，请求尚未产生业务副作用，允许完成验证后重放
一次。

Android UTS SSE上报状态码、Content-Type、CF-Mitigated和CF-Ray，不读取Challenge HTML作为
事件。POST SSE只在尚未收到 `accepted` 时用原幂等键恢复一次；已存在Generation的GET观察
连接可以恢复一次。收到accepted、正文事件或终态后禁止重放POST，避免重复计费。

`uni.connectSocket`错误事件不能可靠提供Cloudflare响应头，因此不修改语音WebSocket恢复逻辑。
语音连接依赖前置Ticket HTTP请求完成Challenge恢复后再建立。

## 安全与隐私

- Worker完成页使用nonce CSP、禁止缓存、禁止嵌入且不包含业务凭据。
- 客户端只识别诊断头，不解析、执行或记录API响应中的Challenge HTML。
- `cf_clearance`不是业务身份凭证，不进入后端身份协议。
- H5不调用Android协调器，不改变现有Cookie、CSRF和Turnstile生命周期。
- 日志最多记录固定错误码和有界CF-Ray，禁止记录Cookie、Token、HTML正文或请求体。

## 验证和发布

第一阶段只提交生产代码和测试源码，并执行 `git diff --check`。第二阶段获得明确授权后运行
Worker、认证网络风险、聊天SSE、HTTPS和语音测试，再由HBuilderX生成Android开发包。Cloudflare
Worker部署和Managed Challenge规则分别需要外部修改授权。

真实验收必须覆盖：交互式或自动Managed Challenge、Cookie状态204、PreAuth成功重试、SSE在
accepted前恢复、重复Challenge停止、用户取消、超时、H5回归和语音Ticket后WebSocket连接。
