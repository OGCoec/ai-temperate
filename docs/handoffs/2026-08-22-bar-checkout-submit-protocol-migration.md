# BAR Checkout 签名 POST 硬切协议：`ai-temperate-main` 上下游迁移交接

## 1. 文档状态

| 项目 | 内容 |
|---|---|
| 交接日期 | 2026-08-22 |
| 目标项目 | `C:\Users\damn\Desktop\ai-temperate-main` |
| BAR 对照项目 | `C:\Users\damn\Desktop\ai-temperate-recall-test` |
| BAR 协议真源 | `C:\Users\damn\Desktop\ai-temperate-recall-test\docs\BAR_PAYMENT_API.md` |
| 变更类型 | 破坏性硬切，不提供旧协议兼容期 |
| BAR 状态 | 新协议代码、自动化测试和外部 Chrome 闭环验收已完成 |
| 主项目状态 | 尚未适配；当前购买按钮仍依赖已删除的旧协议 |
| 被取代文档 | `docs/handoffs/2026-08-21-bar-checkout-refresh-session-recovery.md` |

本文是 `ai-temperate-main` 接入 BAR 新 Checkout 协议的变更说明、影响分析和实施交接。它不授权自动修改代码、部署服务、轮换 API Key 或执行真实外部支付操作；接手者实施前仍应按项目规范单独提交计划并获得批准。

## 2. 结论

BAR 已从“服务器创建订单后返回带 Fragment token 的支付 URL”硬切为“浏览器提交签名 POST Form，由 BAR 设置 HttpOnly Cookie 后 303 到干净支付页”。

旧链路：

```text
ai-temperate-main 后端
  └─ POST BAR /api/pay/create
       └─ JSON 返回 pay_type + pay_url + #token
            └─ ai-temperate-main Vue 执行 location.assign(pay_url)
```

新链路：

```text
ai-temperate-main 后端
  ├─ POST BAR /api/pay/create，预创建订单并取得 trade_no
  └─ 为 BAR /api/pay/submit 生成短时签名字段
       └─ ai-temperate-main Vue 创建临时原生 Form
            └─ 浏览器 POST BAR /api/pay/submit
                 ├─ Set-Cookie: bar_checkout_session=<opaque>
                 └─ 303 → /pay/{tradeNo}
```

因此当前主项目必须修改。不能继续等待 BAR 返回 `pay_url`，不能直接拼接 `/pay/{tradeNo}`，也不能把 `/api/pay/submit` 改成 GET。

## 3. 为什么当前主项目会失效

当前未提交实现仍明确依赖旧合同：

| 层次 | 当前文件 | 旧依赖 |
|---|---|---|
| BAR 客户端 | `ai-temperate-service/.../provider/bar/impl/BarPaymentRestClientImpl.java` | `CREATE_FIELDS` 强制要求 `pay_type/pay_url`，并校验 `pay_type=jump` 和 `#token` 支付 URL |
| Provider 结果 | `ai-temperate-service/.../provider/PaymentCheckoutResult.java` | 结果固定包含 `URI payUrl` |
| 支付发起结果 | `ai-temperate-service/.../order/MembershipPaymentAttemptResult.java` | 向 Web 层继续传递 `payUrl/payUrlExpiresAt` |
| Web DTO | `ai-temperate-web/.../payment/MembershipOrderResponse.java` | 公开 `payUrl/payUrlExpiresAt` |
| Web Controller | `ai-temperate-web/.../payment/CurrentUserMembershipOrderController.java` | OpenAPI 文案声明 BAR 返回 H5 地址 |
| 前端 API | `fornted/common/user/membership-payment-api.js` | 解析并接受 `payUrl/payUrlExpiresAt` |
| 前端安全校验 | `fornted/common/user/membership-payment-state.js` | 强制要求 `/pay/{tradeNo}#token=<43位Base64URL>` |
| 购买页面 | `fornted/pages/account/membership-plans.vue` | `window.location.assign(target)` 导航旧 URL |
| CSP | `fornted/index.html` | `form-action 'self'` 会阻止向 BAR Origin 提交 Form |
| 数据关系文档 | `docs/database/membership-payment-logical-relations.md` | 仍声明 BAR `pay_url` 只随响应传递 |

BAR 新版 `/api/pay/create` 不再返回 `pay_type` 或浏览器跳转地址。当前 `BarPaymentRestClientImpl#createCheckout` 会把合法的新响应判定为 `BAR_RESPONSE_INVALID`，所以购买流程会在主项目后端终止，浏览器还没有机会进入 BAR。

## 4. BAR 外部合同变更

### 4.1 协议差异

| 能力 | 旧协议 | 新协议 |
|---|---|---|
| 服务器创建订单 | `POST /api/pay/create` | 保留 |
| 创建响应 | 包含 `pay_type/pay_url` | 只包含订单与签名字段 |
| 浏览器入口 | 导航 `pay_url#token=...` | 签名 `POST /api/pay/submit` |
| URL token | `#token=...` | 完全删除 |
| token 兑换 | `POST /api/checkout/orders/{tradeNo}/session` | 完全删除 |
| 支付页 URL | `/pay/{tradeNo}#token=...` | 干净 `/pay/{tradeNo}` |
| Checkout 凭证 | URL Fragment 首次兑换 | BAR 在 submit 响应中设置 HttpOnly Cookie |
| 刷新恢复 | 曾依赖 Fragment | 使用 Cookie 直接 GET 快照并重建 SSE |
| `GET /api/pay/submit` | 旧环境可能兼容 | 返回 405 |

### 4.2 `POST /api/pay/create`

调用方仍是 `ai-temperate-main` 后端。请求签名算法、请求字段、幂等键 `(pid,out_trade_no)`、查询、关单、退款和回调协议不变。

成功响应只允许：

```json
{
  "code": 0,
  "msg": "success",
  "trade_no": "1234567890123456789",
  "out_trade_no": "AaAjECcaAQGqi_h2Rl1PiA",
  "expires_at": "2026-08-22T15:15:00Z",
  "created": true,
  "timestamp": "1787410800",
  "key_version": 1,
  "sign_type": "HMAC-SHA256",
  "sign": "<LOWER_HEX_SIGNATURE>"
}
```

新建返回 HTTP 201，完全相同的幂等读取返回 HTTP 200。响应仍必须按 `key_version` 验签；`trade_no` 必须始终按字符串处理。

### 4.3 `POST /api/pay/submit`

调用方必须是用户浏览器提交的顶层 HTML Form：

```http
POST https://ihaveagoddamnplan.com/api/pay/submit
Content-Type: application/x-www-form-urlencoded
```

字段白名单：

```text
pid
out_trade_no
type
name
money
notify_url
return_url
timestamp
key_version
sign_type
sign
```

主项目当前没有使用 `param`，迁移时不要为了兼容未知需求加入该可选字段。以后需要透传信息时必须先单独设计、纳入签名和测试。

成功响应：

```http
HTTP/1.1 303 See Other
Location: https://ihaveagoddamnplan.com/pay/{tradeNo}
Cache-Control: no-store
Set-Cookie: bar_checkout_session=<OPAQUE>; Path=/api/checkout/orders/{tradeNo}; Secure; HttpOnly; SameSite=Strict
```

浏览器自动跟随 303 后，地址栏只出现 `/pay/{tradeNo}`。主项目拿不到也不需要拿 Checkout token。

### 4.4 Checkout 会话语义

- Cookie 是浏览器会话 Cookie，不设置 `Expires/Max-Age`。
- 服务端授权最多 15 分钟，且不超过 BAR 订单 `expires_at`。
- Cookie 生命周期和服务端授权期限相互独立。
- BAR 使用进程内 Caffeine 保存授权；BAR 重启后现有 Checkout Cookie 失效。
- 同一订单再次成功 submit 会覆盖旧授权，旧标签页随后收到 `40103`。
- Cookie Path 只覆盖 `/api/checkout/orders/{tradeNo}`，不会随 `/pay/{tradeNo}` HTML 文档请求发送。
- 支付页不需要管理员登录；管理员 Cookie 与 Checkout Cookie 无关。

上述是 BAR 内部会话边界。`ai-temperate-main` 禁止保存、镜像或代理该 Cookie。

## 5. 推荐的主项目目标架构

### 5.1 固定选择

采用“服务器预创建 + 浏览器签名 submit”两段式方案：

1. 主项目后端继续调用 `/api/pay/create`。
2. 主项目立即绑定 BAR `trade_no`，保留现有主动查询、关单、退款和回调核对能力。
3. 后端使用同一组关键订单参数和一个新的当前时间戳生成 submit 签名字段。
4. Web API 把固定 action、固定 method、短时到期时间和签名字段返回 Vue。
5. Vue 临时创建原生 Form，顶层 POST 到 BAR。

不采用以下替代方案：

- 不跳过 `/api/pay/create`。否则 `trade_no` 只能在回调后绑定，会扩大查询、关单、取消并发和退款改造范围。
- 不让 Java Controller 拼接 HTML。本项目 `AGENTS.md` 明确禁止 Java 源码包含前端展示代码。
- 不用 fetch/XHR 调用 `/api/pay/submit`。本接口的目标是让浏览器接收 Cookie 并完成顶层 303 导航。
- 不使用 302/303 从主项目中转到 BAR；它会把后续请求变成 GET，无法携带签名表单正文。

### 5.2 目标时序

```text
用户点击“立即购买”
  │
  ├─ POST /api/user/membership-orders
  │    └─ 主项目创建本地会员订单
  │
  ├─ POST /api/user/membership-orders/{orderId}/payment-attempts
  │    ├─ PostgreSQL 提交 payment-attempt 事实
  │    ├─ POST BAR /api/pay/create
  │    ├─ 验证 BAR 响应签名
  │    ├─ 绑定 provider_trade_no
  │    ├─ 生成新的 /api/pay/submit 签名字段
  │    └─ 返回 order + checkoutSubmission
  │
  └─ Vue 严格校验 checkoutSubmission
       ├─ sessionStorage 只记录本地 orderId 返回上下文
       ├─ 创建临时 form + hidden inputs
       └─ 浏览器 POST BAR /api/pay/submit
            ├─ BAR 创建/读取同一订单
            ├─ Set-Cookie
            ├─ 303 /pay/{tradeNo}
            ├─ GET Checkout 快照
            └─ 建立 events SSE

管理员确认支付
  ├─ BAR 提交 PAID 并发布 SSE
  ├─ BAR 异步 GET 主项目 notify_url
  └─ BAR 支付页约 2 秒后跳转主项目 return_url

主项目回调
  ├─ 验签
  ├─ 主动 query BAR
  ├─ 核对 trade_no/out_trade_no/money/finished_at
  └─ 幂等提交本地 PAID
```

## 6. 主项目 Web API 合同调整

### 6.1 支付发起接口保持路径不变

```http
POST /api/user/membership-orders/{orderId}/payment-attempts
```

认证、资源所有权、HTTP 201/200 幂等语义和 `Cache-Control: no-store` 保持不变。只调整成功响应结构。

### 6.2 推荐响应结构

不要继续把支付入口字段塞进通用 `MembershipOrderResponse`。支付发起接口改为专用响应，并在 Java Web 边界使用固定字段的 record/DTO，不要把任意 `Map<String, ?>` 直接暴露为公开合同：

```json
{
  "order": {
    "orderId": "AaAjECcaAQGqi_h2Rl1PiA",
    "membershipTier": "PLUS",
    "payAmountYuan": "20.00",
    "payType": "alipay",
    "status": "PENDING_PAYMENT",
    "paymentStartedAt": "2026-08-22T15:00:00Z",
    "expiresAt": "2026-08-22T15:15:00Z",
    "createdAt": "2026-08-22T15:00:00Z",
    "updatedAt": "2026-08-22T15:00:00Z"
  },
  "checkoutSubmission": {
    "provider": "BAR",
    "action": "https://ihaveagoddamnplan.com/api/pay/submit",
    "method": "POST",
    "contentType": "application/x-www-form-urlencoded",
    "submitExpiresAt": "2026-08-22T15:05:00Z",
    "fields": {
      "pid": "1001",
      "out_trade_no": "AaAjECcaAQGqi_h2Rl1PiA",
      "type": "alipay",
      "name": "会员模拟支付订单",
      "money": "20.00",
      "notify_url": "https://niko000o.site/api/payment/bar/notify",
      "return_url": "https://niko000o.site/pages/account/payment-result",
      "timestamp": "1787410800",
      "key_version": "1",
      "sign_type": "HMAC-SHA256",
      "sign": "<LOWER_HEX_SIGNATURE>"
    }
  }
}
```

示例中的 `return_url` 必须以生产配置的实际值为准，不能直接复制文档示例。

`checkoutSubmission` 只在 Provider 为 `BAR` 且本次确实允许浏览器进入支付页时返回；`LOCAL_SIMULATOR` 使用同一个专用响应外壳，但该字段固定为 `null`，前端按 `provider` 显式分支，不能为本地模拟器伪造 BAR Form。

`submitExpiresAt` 必须是“签名时间戳 + 300 秒”、BAR 订单 `expires_at` 和主项目本地会员订单 `expiresAt` 三者中的最早值。本地会员订单期限是浏览器能否继续付款的最终业务边界，浏览器不得在该时间之后提交 Form。Service 缩短 `submitExpiresAt` 时不需要重新签名，因为它只是主项目响应中的提交描述元数据，不属于发送给 BAR 且参与 HMAC 的 Form 字段；它不是 Cookie 期限，也不替代订单 `expiresAt`。

### 6.3 DTO 安全边界

- `action` 必须由服务端固定配置生成，禁止来自客户端请求或 BAR 响应中的任意 URL。
- `action` 由启动期已校验的 `bar.base-url` 加固定 `/api/pay/submit` Path 派生，不新增请求级 URL 覆盖入口。
- `method` 固定为 `POST`。
- `contentType` 固定为 `application/x-www-form-urlencoded`。
- `fields` 在 JSON 中表现为对象，但 Java DTO 必须逐字段建模；只允许本节固定键，禁止包含 API Key、Checkout token、Cookie、内部用户 ID 或任意附加参数。
- 所有字段值都使用字符串，避免数字精度和签名规范化差异。
- 响应、CDN、Service Worker 和浏览器缓存全部 `no-store`。
- Controller、异常和日志禁止输出 `sign` 或完整字段 Map。
- 通用订单 GET 响应不返回 `checkoutSubmission`，避免过期签名被误用。

## 7. 后端修改清单

### 7.1 BAR 客户端合同

涉及：

```text
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/bar/BarPaymentClient.java
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/bar/impl/BarPaymentRestClientImpl.java
ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/bar/impl/BarPaymentRestClientTest.java
```

要求：

- `CREATE_FIELDS` 删除旧跳转字段，只接受 BAR 新响应字段。
- 删除 `pay_type=jump` 和 `pay_url/#token` 校验。
- 保留创建响应状态、`created`、`out_trade_no`、`trade_no`、`expires_at`、时间戳和签名校验。
- 创建请求和 submit 字段必须复用同一个字段构造边界，避免金额、名称、回调 URL 或支付类型不一致触发 `40901`。
- submit 签名必须使用新的当前时间戳，不能复用可能已经接近 300 秒边界的 create 请求 Map。

### 7.2 Provider 与领域结果

涉及：

```text
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/PaymentCheckoutResult.java
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/bar/impl/BarMembershipPaymentProviderImpl.java
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentAttemptResult.java
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentAttemptServiceImpl.java
```

要求：

- 移除 `payUrl` 语义。
- Provider 创建结果保留 `providerTradeNo/expiresAt/created`，并携带独立、不可持久化的 Checkout 提交描述。
- `provider_trade_no` 仍在 `/api/pay/create` 成功并验签后绑定。
- Redis 和 PostgreSQL 只保存订单事实，不保存 submit 签名、完整字段 Map 或 Checkout Cookie。
- 取消并发检查仍在向浏览器返回提交描述前执行；订单已取消或过期时不得泄露仍可提交的字段，并继续幂等关单。

### 7.3 Web DTO 与 Controller

涉及：

```text
ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/CurrentUserMembershipOrderController.java
ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/MembershipOrderResponse.java
ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/MembershipOrderWebContractTest.java
```

要求：

- 从通用 `MembershipOrderResponse` 删除 `payUrl/payUrlExpiresAt`。
- 为 payment-attempts 新增专用响应 DTO；不要在 Java 中拼接 HTML。
- OpenAPI 文案从“返回 H5 地址”改成“返回短时签名 POST 提交描述”。
- 保持认证、资源级所有权、201/200、`Cache-Control` 和 `CDN-Cache-Control: no-store`。
- 测试必须确认响应中不存在 API Key、Checkout token、Cookie 和旧跳转字段。

## 8. 前端修改清单

### 8.1 API 解析

涉及：

```text
fornted/common/user/membership-payment-api.js
fornted/common/user/membership-payment-api.test.cjs
```

新增对 `checkoutSubmission` 的严格解析：

- `provider` 只能是 `BAR`。
- `action` 必须精确等于 `https://ihaveagoddamnplan.com/api/pay/submit`。
- `method` 必须是 `POST`。
- `contentType` 必须是 `application/x-www-form-urlencoded`。
- `submitExpiresAt` 必须可解析、尚未过期，且不能明显晚于本地订单期限。
- `fields` 必须恰好是固定白名单，不允许缺失、重复或额外键。
- `pid/key_version/timestamp/sign` 等格式必须逐项校验。

### 8.2 表单提交辅助函数

涉及：

```text
fornted/common/user/membership-payment-state.js
fornted/common/user/membership-payment-state.test.cjs
```

删除：

```text
validateBarPaymentTarget
BAR_PAY_PATH_PATTERN
BAR_TOKEN_FRAGMENT_PATTERN
```

新增一个职责单一的提交函数：

1. 接收已经严格解析的 `checkoutSubmission`。
2. 创建不可见的 `<form>`。
3. 固定 `method=post`、`action`、`accept-charset=UTF-8`，不设置新窗口 target。
4. 为固定字段创建 hidden input；使用 DOM 属性赋值，不使用 `innerHTML`。
5. 写入页面后调用原生 `HTMLFormElement.prototype.submit.call(form)`。
6. 提交前失败时移除 Form；开始顶层导航后不再执行本地支付成功逻辑。

签名字段可以出现在浏览器提交正文中，这是协议要求；但不得写入 `localStorage`、`sessionStorage`、日志、埋点、错误文本或 URL。

### 8.3 购买页面

涉及：

```text
fornted/pages/account/membership-plans.vue
fornted/pages/account/membership-payment-pages-contract.test.cjs
```

将：

```javascript
window.location.assign(validateBarPaymentTarget(payment.payUrl, payment.payUrlExpiresAt))
```

替换为：

```text
解析 checkoutSubmission
→ 写入只含本地 orderId 的 return context
→ 创建并提交 BAR POST Form
```

不得把 Form 字段保存进 `paymentIntents` 或响应式页面状态。失败重试必须重新请求 payment-attempts，取得新的 timestamp 和 sign；禁止复用超过 `submitExpiresAt` 的提交描述。

### 8.4 CSP

当前：

```text
form-action 'self'
```

会阻止跨 Origin Form POST。源文件至少需要调整为只额外允许 BAR：

```text
form-action 'self' https://ihaveagoddamnplan.com
```

需要核对并同步：

```text
fornted/index.html
fornted/common/auth/https-configuration.test.cjs
Cloudflare 返回的 Content-Security-Policy 响应头及其测试（如果生产响应头另有同名策略）
```

浏览器会同时执行响应头 CSP 和 HTML Meta CSP；任一策略仍为 `form-action 'self'` 都会阻止提交。禁止为了省事改成 `form-action https:` 或 `form-action *`。`fornted/dist/**` 是构建产物，不得手工编辑。

## 9. 保持不变的上下游合同

以下链路不是本次迁移目标：

- BAR `POST /api/pay/query`。
- BAR `POST /api/pay/close`。
- BAR `POST /api/pay/refund`。
- BAR → 主项目 `GET /api/payment/bar/notify` 回调字段与 HMAC 验签。
- 主项目回调后主动查询、核对 `finished_at` 和幂等提交本地支付事实。
- PostgreSQL 会员订单三单关系和 `provider_trade_no` 字段。
- Redis 订单快照与回调队列。
- RabbitMQ 主动查询、关单与补偿任务。
- `5431` 主项目 PostgreSQL、BAR `5430` PostgreSQL及双方端口。
- BAR 只模拟支付和退款；真实会员权益由主项目在可信支付事实确认后原子发放，不由 BAR 发放。

回调成功不是浏览器跳转的前提，浏览器跳转也不是本地记账依据。主项目结果页继续只查询主项目后端订单状态。

## 10. 错误与重试语义

| 场景 | 所在层 | 预期处理 |
|---|---|---|
| `/api/pay/create` 返回 `40102` | 主项目后端 | 映射现有 BAR 认证/签名错误，不向前端泄露响应正文 |
| `/api/pay/create` 返回 `40901` | 主项目后端 | 保留订单冲突错误，不生成 submit 字段 |
| BAR 创建超时 | 主项目后端 | 维持不确定错误语义；重试同一本地订单并主动查询 |
| submit 描述已过期 | 主项目前端 | 不提交，重新请求 payment-attempts 获取新签名 |
| 浏览器错误使用 GET submit | BAR | 405；主项目不得自动改 GET |
| submit 签名错误/过期 | BAR | HTTP 401、`40102`；用户重新从商户入口发起 |
| Checkout Cookie/服务端会话失效 | BAR 支付页 | HTTP 401、`40103`；重新从商户入口发起 |
| 同一订单重复 submit | BAR | 签发新会话并覆盖旧授权；只允许用户显式重试 |
| SSE 连续失败三次 | BAR 支付页 | 每 3 秒轮询快照 |
| 回调失败 | BAR/主项目 | BAR 保持 PAID；管理员可手动重试，主项目保持幂等 |

主项目不得把 BAR 浏览器错误误判为当前用户登录失效。支付页不依赖 BAR 管理员登录，也不依赖主项目登录 Cookie。

## 11. 测试要求

### 11.1 后端单元与契约测试

- 新 `/api/pay/create` 响应没有旧跳转字段时能够通过验签和解析。
- 响应出现未知字段、错误 trade_no、错误 out_trade_no、过期时间或错误签名时失败。
- submit 字段严格使用与 create 相同的金额、名称、支付类型、回调 URL 和返回 URL。
- submit 使用新的 timestamp/sign，并计算 `submitExpiresAt`。
- payment-attempts 201 和幂等 200 均返回可用的新提交描述。
- 通用订单 GET 不返回提交描述。
- 取消或终态并发后不返回提交描述，并继续执行既有幂等关单。
- 响应序列化不包含 API Key、Checkout token、Cookie 或旧跳转字段。

### 11.2 前端单元与合同测试

- 接受唯一允许的 BAR action、POST、Content-Type 和字段白名单。
- 拒绝 HTTP、错误 Host、非默认端口、Query、Fragment、用户信息和其他 Path。
- 拒绝过期 `submitExpiresAt`、错误 timestamp、错误 sign 格式、缺少字段和额外字段。
- 创建 Form 时每个字段只出现一次，不使用 `innerHTML`。
- 提交前只把本地 orderId 写入 return context。
- 不调用 `location.assign(payUrl)`，不读取或生成 `#token`。
- CSP 只额外允许 BAR 精确 Origin。
- 构建产物中不出现 API Key 原文或 Checkout token。

### 11.3 回归测试

- 回调验签、主动 query、关闭、退款和退款补偿测试继续通过。
- Redis/RabbitMQ/数据库状态机测试继续通过。
- 本地模拟 Provider 行为不因 BAR 提交描述而被迫生成外部 Form。
- 当前用户资源所有权、CSRF、会话认证和 no-store 合同继续通过。

## 12. 部署顺序

BAR 新协议已经硬切，因此主项目适配必须作为一个完整前后端发布单元；发布窗口内不能让旧 H5 与新后端、或新 H5 与旧后端混合提供购买入口：

1. 完成主项目后端新 create 响应解析与 submit 描述生成。
2. 完成 Web DTO、OpenAPI 和 no-store 合同。
3. 完成 Vue Form 提交与 CSP 精确放行。
4. 运行后端、前端和 Cloudflare 契约测试。
5. 发布前设置 `app.membership-payment.checkout-enabled=false`，暂停创建新的支付入口；查询、回调、关单和退款消费者继续运行。
6. 在同一维护窗口部署主项目后端、同版本 H5 和 Cloudflare CSP。
7. 清理或失效旧 H5 的 CDN/Service Worker 缓存，并确认生产响应头与 Meta CSP 都已允许精确 BAR Form Origin。
8. 恢复 `checkout-enabled=true`。
9. 使用有效 API Key 从主项目外部 Chrome 发起全新订单验收。

如果后端先部署而旧前端仍在线，旧前端会因缺少 `payUrl` 拒绝响应；如果前端先部署而旧后端仍在线，新前端会因缺少 `checkoutSubmission` 拒绝响应。因此本次硬切使用“暂停新 Checkout + 同窗发布 + 缓存失效”作为版本门禁，不额外发明旧客户端协商协议。

## 13. 人工验收矩阵

| 场景 | 预期结果 |
|---|---|
| 主项目点击购买 | 本地订单创建，payment-attempts 返回签名 submit 描述 |
| 浏览器提交 | Network 出现 `POST https://ihaveagoddamnplan.com/api/pay/submit` |
| BAR 响应 | 303 后地址栏只有 `/pay/{tradeNo}`，没有 token |
| BAR Cookie | Session、Secure、HttpOnly、SameSite Strict，订单 API Path |
| 支付页刷新三次 | 每次恢复相同订单并重新建立 SSE |
| SSE 正常 | Network 中 `events` 类型为 `eventsource`，不启动轮询 |
| SSE 连续失败三次 | 每 3 秒 GET 快照并仍可发现 PAID |
| 管理员确认支付 | 支付页识别 PAID，约两秒后只跳转一次 return_url |
| 主项目回调 | 验签、主动查询和本地 PAID 幂等完成 |
| 回调失败 | BAR 仍为 PAID，支付页仍可返回，主项目不误记账 |
| 重复点击提交 | 只允许显式重试；新标签页有效，旧标签页收到 40103 |
| BAR 重启 | 旧 Checkout 会话失效，重新从主项目入口提交即可 |
| submit 签名超过 300 秒 | 前端拒绝旧描述并重新请求，不复用签名 |

至少连续执行五笔新订单；每笔刷新三次，再分别验证 SSE 和回调。一次成功只能证明链路可用，不能代替重复稳定性验收。

## 14. 回滚边界

BAR 不恢复旧 `#token`、旧 session 兑换接口或旧创建响应，因此不能通过回滚主项目前端到旧版本恢复购买。

发生主项目适配故障时，安全回滚方式是：

1. 设置 `app.membership-payment.checkout-enabled=false`，停止创建新的支付入口。
2. 保留查询、回调、关单和退款消费者，继续收敛已创建订单。
3. 回滚主项目后端与 H5 到同一兼容版本，禁止只回滚一侧。
4. 修复后重新部署新协议；不在 URL、数据库、Redis 或前端 Storage 中恢复 token。

禁止通过放宽 CSP 到所有 HTTPS、把 API Key 下发浏览器、跳过响应验签或恢复 GET submit 作为临时修复。

## 15. 文档同步清单

实施代码时同步更新：

- `docs/database/membership-payment-logical-relations.md`：删除旧 `pay_url` 描述，改为短时 submit 描述不持久化。
- `CurrentUserMembershipOrderController` 的 OpenAPI 描述。
- 前端接口合同测试与支付页面合同测试。
- CSP 安全测试。
- 项目运行配置说明：API Key 仍只通过后端环境变量提供。

BAR 的公开商户协议以其 `/docs/` 和 `docs/BAR_PAYMENT_API.md` 公开标记区段为准。主项目交接文档不得复制 BAR 管理员、Caffeine KV 或数据库内部实现作为商户必须依赖的外部合同。

## 16. 完成标准

- 主项目不再引用 BAR 旧 `pay_url/pay_type/#token` 合同。
- `/api/pay/create` 新响应可以验签、绑定 `trade_no` 并保留现有补偿链路。
- 用户浏览器通过签名 POST Form 进入 BAR，BAR Cookie 和 303 生效。
- API Key 永不进入浏览器、HTML、URL、日志、数据库、Redis 或 RabbitMQ。
- submit 签名字段只在一次 payment-attempts 响应和紧随其后的 Form POST 中短暂存在。
- CSP 只精确放行 BAR Form action。
- 支付页刷新、SSE、轮询、PAID 两秒跳转和回调闭环通过五笔重复验收。
- Redis/RabbitMQ 状态机、回调验签、查询、关单和退款协议保持不变；主项目数据库新增订单权益裁决与单活动订单索引，支付成功后原子发放套餐。

## 17. 给接手任务的简短指令

```text
请只修改 C:\Users\damn\Desktop\ai-temperate-main，并保留当前脏工作区中的用户修改。

BAR 已硬切为浏览器签名 POST /api/pay/submit，旧 pay_url/pay_type/#token 和 Checkout /session 兑换接口已经删除。先按 TDD 修改 BarPaymentRestClientImpl，使 /api/pay/create 只解析并验签 trade_no/out_trade_no/expires_at/created 等新响应字段；继续绑定 provider_trade_no。

随后让后端为同一订单生成新的短时 submit 签名字段，通过 payment-attempts 专用 no-store DTO 返回固定 action、POST、Content-Type、submitExpiresAt 和白名单 fields。Java 不得拼接 HTML，API Key 不得进入响应。

Vue 严格校验提交描述，使用 DOM API 创建临时隐藏 Form，顶层 POST 到 https://ihaveagoddamnplan.com/api/pay/submit。删除 payUrl/#token 校验和 location.assign 旧路径；sessionStorage 只保留本地主项目 orderId。将 CSP form-action 从 self 精确扩展为 self + BAR Origin，并同步安全测试。

不要修改数据库表、Redis/RabbitMQ 状态机、BAR 回调、查询、关单、退款或真实资金边界。完成后按本文测试与五笔 Chrome 验收矩阵验证。
```
