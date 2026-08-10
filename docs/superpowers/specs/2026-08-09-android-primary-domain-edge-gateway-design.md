# Android 主域名边缘网关设计

## 目标

生产 H5 与 Android 对外统一只访问 `niko000o.site`，由 Cloudflare Worker 将受控请求反向代理到 `api.niko000o.site`。当前没有已发布 APK，因此不设置旧版 Android 兼容期；`api.niko000o.site` 继续存在，但只作为 Worker 回源。

最终链路：

```text
H5 / Android HTTP、SSE
  -> https://niko000o.site/api/**
  -> Cloudflare Worker
  -> https://api.niko000o.site/api/**
  -> Java 后端

H5 / Android 语音 WebSocket
  -> wss://niko000o.site/ws/voice
  -> Cloudflare Worker
  -> wss://api.niko000o.site/ws/voice
  -> Java WebSocket
```

## 非目标

- 不删除 `api.niko000o.site` DNS、Tunnel 或 Worker 上游配置。
- 不改变 Android 的 Access Token、Refresh Token、PreAuth Token 与设备安装 ID 协议。
- 不把 Android 改成 H5 Cookie 会话。
- 不改变语音 Ticket 格式、Redis Key、Whisper 协议或业务消息格式。
- 不使用可执行 JavaScript 的 Cloudflare Challenge Page 作为 API 客户端验证手段。
- 不修改 Cloudflare WAF、Bot 产品或 Security Events 规则；生产仍出现 HTML Challenge 时按外部阻塞处理。

## 方案比较

### 方案 A：只替换 Android 前端 API 基址

把 `https://api.niko000o.site` 直接改为 `https://niko000o.site`。

该方案不采用。现有 Worker 会要求 H5 Cookie Scope 标记并返回 `428 EDGE_COOKIE_SCOPE_RESET_REQUIRED`；Android WebSocket 还可能因为 Worker 添加 H5 Origin 而与 Android Ticket 平台不匹配。

### 方案 B：主域名共享网关，保留平台运输协议

H5 与 Android 使用相同公网 Host 和 Worker 路由，但 Worker 明确区分浏览器 Cookie 运输与 Android Header 运输。后端业务协议不变，Worker 为两类请求生成相同规范的边缘 HMAC。

该方案为选定方案。它满足统一域名目标，也不把 Android Token 暴露给 H5 JavaScript。

### 方案 C：保留 Android 二级域名，只调整 WAF

该方案可以解决当前 Cloudflare Challenge，但不能满足 Android 与 H5 统一公网域名的目标，因此不采用。

## 请求分类与安全边界

Worker 将主域名请求分为两类：

1. H5 请求：带受信任主域 Origin，继续要求 Cookie Scope 标记并使用 Cookie/CSRF 协议。
2. Android 原生请求：`X-Client-Platform` 为 `ANDROID`，且没有浏览器 Origin 与 Fetch Metadata；即使入站携带 Cookie 也会在回源前删除，继续使用显式 Token Header。

`X-Client-Platform` 本身不是身份凭证。该分类只选择运输协议，不授予用户身份；用户身份仍由 PreAuth、Access Token、Refresh Token、CSRF、设备绑定与一次性语音 Ticket 校验。无 Origin 的非浏览器客户端可以模仿 Android，因此即使公网已经统一经过 Worker，也不得把该分类描述为设备证明。

如果未来要求证明请求来自未篡改 APK，应单独引入服务端验证的设备证明或硬件密钥协议，不能把 APK 内置共享 Secret 当作长期安全边界。

## Worker HTTP 行为

- 保持现有主域 `/api/**` 精确白名单。
- H5 请求继续执行 Cookie Scope 迁移门槛。
- Android 原生请求跳过 H5 Cookie Scope 门槛，但必须拒绝或删除 Cookie。
- Android 请求保留 `Authorization`、`X-Refresh-Token`、`X-CSRF-Token`、`X-AIT-PreAuth`、`X-Device-Installation-Id` 与平台头。
- 两类请求都清除客户端伪造的 `X-AIT-Edge-*`、`X-Forwarded-*` 和其他代理身份头，再由 Worker 写入新的 HMAC 与可信网络上下文。
- Android 回源不添加浏览器 Origin；HMAC 中的外部 Host 仍绑定 `niko000o.site`。
- Android 上游响应不得设置 Cookie；出现 `Set-Cookie` 时由 Worker 返回受控 `502`。
- H5 继续使用现有响应 Cookie 白名单。

## Worker WebSocket 行为

- 公网地址统一为 `wss://niko000o.site/ws/voice`。
- H5 Upgrade 继续要求 Cookie Scope，但回源前删除 Cookie 与 Authorization。
- Android Upgrade 不要求 H5 Cookie Scope，且必须没有浏览器 Origin；回源前同样删除 Cookie 与 Authorization。
- Android 回源不添加 H5 Origin，使后端继续按无 Origin + Android Ticket 协议校验。
- H5 回源继续使用 `Origin: https://niko000o.site` 与 H5 Ticket。
- Worker只代理握手和双向通道，不读取音频帧、不调用 `accept()`、不重建有效 `101` Response。

## 前端行为

- 生产 H5 保持同源空 API 基址。
- Android API 基址改为 `https://niko000o.site`。
- 本地 H5 继续使用 `https://localhost:6655`。
- Android HTTP、SSE、预上传与认证请求都通过共享 `AUTH_API_BASE_URL` 进入主域名。
- Android 语音 WebSocket由新的主域名 API 基址生成 `wss://niko000o.site/ws/voice`。
- 平台协议仍由 `clientPlatform()` 控制，不通过域名推断平台。

## Java 后端行为

现有业务认证协议保持不变，同时收紧公网边缘入口：

- Worker签名完整时，边缘过滤器允许请求并注入可信网络上下文。
- 生产 `EDGE_PROXY_MODE=REQUIRED` 对 `/api`、`/api/**` 与精确 `/ws/voice` 全部要求完整有效的 Worker 签名；无 Origin Android 不再具有直连例外。
- `OPTIONAL` 只允许完全不带边缘头的切换期请求；携带部分、过期或错误边缘头仍然拒绝。
- `DISABLED` 只供本地开发跳过边缘验签。
- Android平台头继续选择 Header Token运输。
- 无 Origin 的 Android WebSocket继续要求 Android一次性 Ticket。
- H5仍使用主域 Origin、Cookie、CSRF 与 H5 Ticket。

## Cloudflare WAF

WAF 调整不属于本次代码实施范围。API、SSE 或 WebSocket Upgrade 如果仍收到带有 `CF-Mitigated: challenge` 的 HTML 响应，说明请求在到达 Worker 应用逻辑前后被 Cloudflare 安全产品挑战；该情况必须作为独立外部阻塞报告，不能通过放宽本次 Worker 分类或后端验签来绕过。

## 测试设计

第一阶段编写但不执行以下测试源码：

- 前端契约：Android生产 API基址为主域名；生产源码不再包含 Android直连 `api.`；本地 H5与生产 H5行为不变。
- 前端语音契约：Android与H5生产地址都生成主域名 WSS，本地 H5不变。
- Worker HTTP：Android无 Origin请求成功分类并签名回源；入站 Cookie被删除、上游 Set-Cookie被拒绝；H5仍要求Cookie Scope。
- Worker WebSocket：Android无 Origin Upgrade通过主域名回源且上游无 Origin；H5仍带主域 Origin；两类请求均不泄漏Cookie/Authorization。
- Worker安全：浏览器请求不得通过伪造 `ANDROID` 头绕过Cookie Scope；伪造边缘头继续被清除。
- Java定向测试：REQUIRED 拒绝无签名 Android API 与 WebSocket，接受无 Origin 但签名有效的 Android请求；OPTIONAL、DISABLED 与非保护路径语义保持不变。

第二阶段获得明确授权后，才运行前端、Worker和必要Java测试；不得连接生产数据库、Redis、RabbitMQ或Whisper。

## 部署与收口

1. 记录当前Worker、Android与后端可回滚版本。
2. 先部署支持两类运输协议的新Worker。
3. 用隔离请求验证主域名Android HTTP、SSE与WebSocket代理。
4. 在HBuilderX重新生成并安装Android开发包，使其切换到主域名。
5. 验证Android与H5的PreAuth、登录、Token续期、SSE、上传与语音指标。
6. 最后部署后端签名收口，并保持生产 `EDGE_PROXY_MODE=REQUIRED`，立即拒绝所有无Worker签名的API与语音握手。
7. 验证 `api.niko000o.site` 无签名直连返回签名403，主域名经Worker仍成功。
8. `api.niko000o.site` 继续作为Worker回源，不删除DNS或Tunnel。

若后端已经完成 REQUIRED 收口，回滚时必须先恢复后端的临时兼容入口，再恢复Android旧基址；Worker最后回滚，避免主域名客户端先失去代理入口。

## 成功标准

- Android抓包中所有自有HTTP、SSE与语音WebSocket都只显示 `niko000o.site`。
- Worker回源仍为 `api.niko000o.site`。
- Android不依赖H5 Cookie Scope，不接收H5认证Cookie。
- H5现有Cookie、CSRF、SSE与语音行为不回归。
- `api.niko000o.site` 的无签名 `/api/**` 与 `/ws/voice` 直连被生产后端拒绝。
- 若仍收到Cloudflare HTML Challenge，明确归因于本次范围外的WAF或Bot规则并阻止生产验收，不修改代码绕过。
