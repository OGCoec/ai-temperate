# 网络风险与 Cloudflare WAF Challenge 上线手册

## 1. 上线边界

本功能由 Cloudflare WAF、API Gateway Worker、Spring Boot、Redis 7.4.9 和两个前端共同组成。
它不新增 PostgreSQL 表，不使用 RabbitMQ，也不会自动修改现有 WAF 自定义规则。

生产链路：

```text
浏览器
→ Cloudflare WAF
→ ai-temperate-api-gateway Worker
→ EdgeProxySignatureFilter（v2）
→ NetworkRiskInterceptor
→ WebRtcVerificationInterceptor
→ 业务认证与 Controller
```

## 2. 必需 Secret

以下值必须通过部署环境或 Secret 管理服务提供，不得写入 YAML、仓库、日志或前端：

```text
NETWORK_RISK_HMAC_SECRET_BASE64
IP2LOCATION_API_KEY_ENCRYPTION_KEY_BASE64
EDGE_PROXY_HMAC_SECRET_BASE64
WEBRTC_IP_ENCRYPTION_KEY_BASE64
```

每个值都必须是至少32字节随机数据的标准 Base64。`NETWORK_RISK_HMAC_SECRET_BASE64` 用于
IP、设备、PreAuth、会话引用和风险上下文的用途隔离 HMAC；IP2Location 主密钥通过用途标签
派生 AES-256-GCM 密钥与 Key ID HMAC 密钥。WebRTC 密钥必须恰好解码为32字节，用于单独保护
PreAuth 中的完整候选 IP 集合，禁止复用前述任一密钥。

`AUTH_PHONE_COUNTRY_BIN_PATH` 指向本地 IP2Location LITE DB11 BIN，是第三方地理信息缺失时的
最后本地降级来源。

PreAuth v6 的 WebRTC 候选集合密文字段使用上述独立加密 Secret。距离阈值可按部署环境使用
`NETWORK_RISK_IMPOSSIBLE_TRAVEL_MINIMUM_DISTANCE_KM` 覆盖，未配置时为二百公里；时间窗口继续
使用 `NETWORK_RISK_IMPOSSIBLE_TRAVEL_WINDOW`，未配置时为二十四小时。这两个变量都不是密钥。

## 3. 分阶段切换

### 3.1 后端兼容部署

PreAuth v6、带 `probeGeneration` 的 Start/Report 协议和两个前端后台探测器必须作为同一发布批次上线；
本版本不接受缺少 generation 的旧 Report，也不提供 v4/v5 双读，禁止长期混跑新后端与旧前端。

1. 配置本节列出的全部 Secret，并确认 WebRTC 密钥与其他安全域完全独立。
2. 设置 `NETWORK_RISK_MODE=OBSERVE`。
3. 保持后端同时接受 Edge v1 与 v2 签名。
4. 部署后端，但不要立即启用动态阻断。

OBSERVE 模式会在 PreAuth 与 v2 网络上下文均可用时计算风险，但不会因为缺少新 PreAuth、
缺少 v2 网络上下文、计算异常或 `ALLOW/CHALLENGE/BLOCK` 结果中断旧客户端业务请求。
Edge Proxy 自身的无效签名仍由 `EdgeProxySignatureFilter` 拒绝，不能把 OBSERVE 当作伪造边缘头的绕过。

### 3.2 Worker v2

部署 Worker 后确认它：

- 覆盖并删除客户端伪造的代理与 `X-AIT-Edge-*` 请求头。
- 从 `request.cf` 获取 IP、国家、ASN、纬度和经度。
- 将全部网络字段、Method、Path+Query、外部 Host、Timestamp 和 CF-Ray 绑定到 v2 HMAC。
- 放行 `/api/_edge/pre-auth`、`/api/admin/_edge/pre-auth` 以及两条精确 Challenge 路径。
- 保持普通与管理员路径隔离，不开放可由请求参数改变的上游。

### 3.3 前端

两个 H5 必须在任何认证、会话恢复或业务 API 前完成 PreAuth Bootstrap。PreAuth Cookie 为
Host-only、Secure、HttpOnly；浏览器 JavaScript 不读取其原值。

PreAuth 网络风险通过后，客户端从普通或管理员 WebRTC Start 接口取得固定四个 STUN 地址，使用
浏览器或 Android 系统 WebView 在最长15秒内收集所有公网 `srflx` IPv4/IPv6，再一次性 Report。
后端不向 STUN 发请求。普通端和管理员端都把探测作为后台任务：PreAuth 建立后即可加载页面并发送
业务 HTTP 请求，不等待 Report；服务端在固定20秒窗口内把 `PENDING` 视为临时放行，Report 失败或
服务端超时形成 `FAILED` 后，只拦截后续新请求，已经进入 Controller 的请求不做中途撤销。
同一 HTTP IP 已校验成功时客户端不得重复创建 PeerConnection。

Android 只使用屏幕外的本地 `hybrid/html` WebView、内存 nonce 和一次性 AES-256-GCM Key；候选集合
通过受控自定义 scheme 以密文返回 JS Service，处理完成立即销毁 WebView，不新增原生插件或媒体权限。
校验失败为当前 PreAuth 的终态，失败页不提供人工重试；只有 HTTP 出口变化才由 Lua 增加 generation
并开启新的后台探测窗口。

`NETWORK_RISK_MODE=DISABLED` 时 Bootstrap 返回 `status=DISABLED`，不创建 Redis PreAuth 或
Cookie；这用于 localhost 和紧急回滚。切换到 OBSERVE/ENFORCE 后，前端会重新建立真实 PreAuth。

Android 使用 `X-AIT-PreAuth`，原始值只保存在 AndroidKeyStore 加密载荷中，不复用
`Authorization`。

收到 `RISK_CHALLENGE_REQUIRED` 后必须顶层导航，不使用 XHR/fetch。完成页只恢复同域的
path、query 和 hash，不自动重放非幂等请求。普通端和管理员端共享三轮有限状态机：每轮返回
只复查一次 PreAuth，第三轮仍返回 Challenge 时进入可恢复的失败安全门，禁止第四次自动导航。

### 3.4 二进制 IP HMAC v2、IP 缓存 v3 与 PreAuth v6 单 Hash

IP 风险身份不再使用 IP 展示字符串参与 HMAC。固定输入格式为
`UTF8("risk-ip:v2") + 0x00 + 地址族标识 + 地址网络字节`：IPv4 使用 `0x04 + 4 bytes`，
IPv6 使用 `0x06 + 16 bytes`。IPv6 压缩、展开、大小写和前导零差异因此不会产生不同摘要；
IPv4-Mapped IPv6 统一归一为 IPv4。访问审计 PostgreSQL/MQ 中的 `ip_hmac v1` 不在本次迁移范围内。

IP 情报缓存使用 v3 命名空间，Key 是否存在是唯一有效期依据：

```text
ait:<env>:risk:ipintel:v3:ip:<IP摘要>
ait:<env>:risk:ipintel:v3:single-flight:<IP摘要>
```

普通与管理员使用彼此隔离的 PreAuth v6 命名空间：

```text
ait:<env>:risk:preauth-user:v6:token:<PreAuth摘要>
ait:<env>:risk:preauth-admin:v6:token:<PreAuth摘要>
```

首次 Bootstrap 必须先执行共享 IP 缓存、IP2Location、iPing、本地 BIN 和默认六十分的降级链，
并将当前信用分、风险来源、地理来源、国家、ASN、坐标及网络类型复制到当前 PreAuth Hash。
首次基础分低于四十分时阻断三十分钟，四十至五十九分进入 Challenge，六十分及以上建立可信基线。
首次请求不计算不可能旅行。

v6 Hash 同时保存不可能旅行事件的有界 JSON、派生事件数、Challenge 签发/通过次数以及活动
Challenge 的 Nonce、IP 摘要、上下文摘要和过期时间。不再创建独立 Travel ZSet 或 Challenge
引用 Key；状态不再保存 `evaluatedAt/currentIpEvaluatedAt`，业务层也不再计算固定六小时新鲜度。
WebRTC 使用 `webRtcPhase`、`webRtcGeneration`、`webRtcDeadlineAt`、`webRtcFailureReason` 与
`webRtcIps` 建模 `REQUIRED → PENDING → VERIFIED/FAILED` 四态异步门禁；候选集合是绑定作用域、
PreAuth Token 摘要和当前 HTTP IP 摘要的 AES-256-GCM 密文。创建或 HTTP IP 摘要变化时，Lua
原子建立八秒 `REQUIRED` start grace；GET start 才使用 Redis `TIME` 开启最长十五秒的
`PENDING` report 窗口，重复 start 不续期。业务 GET/POST/PUT/PATCH/DELETE 在 REQUIRED/PENDING
期间全部放行，只有 FAILED 从下一次请求起拦截；认证、Session、CSRF 与 PreAuth 绑定仍同步执行。

登录旋转只在旧状态为 VERIFIED 且 HTTP IP 相同时继承成功，候选集合先解密再使用新 Token 摘要
加密，避免复制旧 AAD 密文后失效；其他阶段在新 Token 上建立新的 REQUIRED generation。Report
与超时通过 Lua 原子竞争，等于 deadline 时仍接受 report，大于 deadline 才写 START_TIMEOUT 或
REPORT_TIMEOUT。任何终态都不能被迟到结果覆盖，旧 generation 或旧 HTTP IP 的 report 返回 409。
服务端通过 `X-AIT-WebRTC-State` 与 `X-AIT-WebRTC-Generation` 通知 H5/Android 后台执行，响应头
不得携带原始 IP、Token、设备 ID 或候选集合。
正向 TTL、fallback TTL 与抖动只决定 Redis 写入期限；管理员手动延长 TTL 或设置永久 Key 后，
只要 v2 JSON 结构合法，该评分就继续有效。部署期间不轮换 HMAC Secret，也不双读或原地改写旧状态。

项目上线前必须受控清空旧 IP 情报、single-flight、普通 PreAuth 和管理员 PreAuth Key，再以
`NETWORK_RISK_MODE=OBSERVE` 发布并重新加载两端以建立 v6 PreAuth。旧版本不提供在线迁移或双读；
确认 PreAuth 初始化、IPv6 摘要、
登录状态码和 Challenge 指标正常后才能恢复 `ENFORCE`。

WebRTC 状态迁移统一观察 `webrtc_state_transition_total`，transition 仅允许
`required_created/required_started/required_timeout/pending_verified/pending_failed/stale_report/generation_changed`；
标签只允许 `scope/platform/reason/mode`，禁止加入 generation、IP、Token 或设备摘要。

后续请求只要能读取合法 Redis IP 快照就直接复用；Key 缺失、损坏或版本不匹配才重新进入共享查询链。
不可能旅行
要求距离至少二百公里、时间差不超过二十四小时且速度至少每秒三百四十米。本次命中固定扣三十分，
三十分钟内不同事件超过五次再固定扣二十分，两项都不按次数继续累加。

### 3.5 强制模式

只有以下项目全部完成后才设置 `NETWORK_RISK_MODE=ENFORCE`：

1. 生产 Worker 请求均使用有效 v2 签名。
2. 普通和管理员 PreAuth Bootstrap 均成功。
3. WAF 两条 Challenge 精确路径已启用且不会循环。
4. Redis 版本支持 `HPEXPIREAT`/`HEXPIRE` 字段 TTL。
5. IP2Location Key 池至少有一个可用 Key，或已确认本地降级行为可接受。
6. 普通、管理员登录与 Session 刷新已验证 PreAuth 旋转及联合续期。
7. 普通/管理员 H5 与 Android 已完成 IPv4-only、IPv6-only、双栈、UDP 阻断和网络切换验收。

第一阶段完成并不满足上述启用条件。在第二阶段测试、Worker/后端部署和浏览器闭环验收完成前，
现有 `AIT-RISK-CHALLENGE` WAF 规则必须继续保持禁用。

稳定后再移除 Edge v1 兼容。

## 4. IP2Location Key 导入

管理员接口受现有管理员 Session、CSRF、设备与 PreAuth 校验保护：

```text
POST /api/admin/risk/ip2location/keys/batch
POST /api/admin/risk/ip2location/keys/import
GET  /api/admin/risk/ip2location/keys
POST /api/admin/risk/ip2location/keys/delete
```

导入时必须显式提供计划类型、初始配额和到期时间。响应只返回数量和脱敏元数据，不返回 API Key。
Redis 中 `secret` Hash 保存 AES-GCM 密文，`quota` Hash 保存剩余额度；两个同名字段具有相同
绝对过期时间。

## 5. 监控

上线观察至少包括：

- Edge v1/v2 验签成功率与失败原因类别。
- `PREAUTH_REQUIRED`、`RISK_CHALLENGE_REQUIRED`、`RISK_BLOCKED` 数量。
- IP 信誉缓存命中率、single-flight 等待、Bulkhead 拒绝和8秒预算耗尽。
- IP2Location、iPing 的成功、鉴权失败、额度耗尽、限流、超时和5xx。
- Challenge 引用签发、同上下文复用、过期、绑定不匹配和一次性消费结果。
- 普通/管理员作用域不匹配事件。

第一阶段已经提供以下低基数 Micrometer 指标：

```text
network.risk.decision
network.risk.rejection
network.risk.ipintel.cache
network.risk.ipintel.lookup
network.risk.ipintel.provider
network.risk.challenge
webrtc_verification_total
webrtc_interceptor_total
```

`network.risk.ipintel.lookup` 的结果标签区分 single-flight owner/wait、Bulkhead 降级、总预算
超时、异常降级和供应商/本地来源；`network.risk.challenge` 区分 issued、consumed 和 rejected。

指标标签不得包含明文 IP、完整摘要、Token、设备标识、API Key 或第三方原始响应。
WebRTC 指标仅允许固定的 scope、outcome/decision、platform 与 mode 标签，也不得使用 STUN URL。
上线观察必须区分 `pending_allowed`、`matched`、`mismatch`、`timeout`、`stale` 与 `network_changed`，
确认普通端和管理员端在 PENDING 期间均没有因为等待 Report 产生请求阻塞。

## 6. 回滚

1. 首先把 `NETWORK_RISK_MODE` 切回 `OBSERVE`；该模式不再强制新 PreAuth 或 v2 风险上下文。
   如需完全停止风险状态处理，再切到 `DISABLED`。
2. 保留 Worker v2 和 Edge v1/v2 后端兼容，避免立即回滚签名导致流量中断。
3. 禁用两条风险 Challenge WAF 规则，但不要改动静态越权 Block 规则。
4. 前端停止自动 Challenge 导航；如风险模式已禁用，可暂时保留 PreAuth Bootstrap。
5. 不恢复明文 API Key，不删除加密 Key 池；通过管理员接口停用或删除异常 Key。

回滚不会恢复已经过期或撤销的 PreAuth、Refresh Session 或管理员 Session。旧程序看不到新建的 v5 PreAuth，
因此回滚后也会再次触发 Bootstrap 和重新登录；旧 IP 情报命名空间若仍存在，也只由对应旧程序读取。

## 7. 第二阶段验证候选范围

以下命令仅供用户另行批准第二阶段后执行，第一阶段不得自动运行：

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest="*NetworkRisk*,*IpIntelligence*,*Ip2Location*,*PreAuth*,*RiskChallenge*" `
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl ai-temperate-web -am `
  -Dtest="*NetworkRisk*,*EdgeProxy*,*Ip2Location*,*LoginControllerTokenTransport*" `
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl ai-temperate-service,ai-temperate-web -am `
  -Dtest=RedisKeyFactoryTest,NetworkRiskPropertiesTest,PreAuthV5SingleHashContractTest,WebRtcVerificationServiceImplTest,RedisPreAuthStoreWebRtcTest,WebRtcVerificationInterceptorTest,WebRtcVerificationInterceptorAsyncIdempotencyTest,WebRtcEdgeControllerTest `
  -Dsurefire.failIfNoSpecifiedTests=false test

Push-Location cloudflare/api-gateway
npm test
Pop-Location

Push-Location fornted
npm run test:auth-network-risk
Pop-Location

Push-Location myuniappadmin
npm run test:auth-network-risk
Pop-Location
```

后端定向测试默认只使用 Mock 和本地测试配置，不连接生产服务。
`RedisIp2LocationApiKeyStoreIntegrationTest` 会额外要求本机 Docker，并启动隔离的
`redis:7.4.9-alpine` Testcontainer；它会写入并销毁容器内测试数据，不连接生产 Redis。
Worker 与前端测试使用本地运行时和源文件契约，不部署 Cloudflare、不修改 WAF。

只有上述定向范围通过并再次得到用户批准后，才考虑执行：

```powershell
mvn clean verify
mvn dependency:tree
```
