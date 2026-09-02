# 六号易支付 V2 接入说明

## 实现边界

会员支付继续使用统一的 `MembershipPaymentProvider` 接口。BAR、六号易支付和本地模拟器分别提供实现；普通前端只公开 BAR 与六号易支付，每个 Provider 均支持支付宝、微信支付两种选择。

六号支付宝由本项目后端调用 `/api/pay/create`。六号微信则由后端向 `/api/pay/submit` 提交一次 RSA 表单，并在关闭自动重定向的 HTTP 客户端上捕获第一跳 `302/303 Location`；Location 路径中的候选交易号必须再通过 `/api/pay/query` 的签名响应确认订单归属、交易号和金额。只有确认完成并把真实 `trade_no` 原子绑定到 PostgreSQL 与 Redis 后，后端才向浏览器返回同源 `/pay/qrcode/<trade_no>/` 页面。前端不生成或解析二维码，也不通过浏览器 `fetch` 调用六号接口。

## 不新增订单字段的路由约定

本次不增加 `membership_order.provider`，也不修改回调表和 Redis 快照结构。Provider 路由只允许复用真实 `provider_trade_no`：

```text
BAR:TRADE:<BAR 流水号>
LIUHAO:TRADE:<六号流水号>
```

新订单的 `provider_trade_no` 必须为 `NULL`。支付尝试请求明确选择 BAR 或六号，取得真实平台流水后只允许执行 `NULL -> PROVIDER:TRADE:<真实流水>`；禁止保存 `PROVIDER:ORDER:<本地订单号>`，也禁止对缺失或无前缀记录使用默认 Provider 猜测路由。前缀只用于后端选择实现，调用第三方接口前必须拆除前缀。

## Secret 与环境变量

```text
LIUHAO_PAYMENT_ENABLED
LIUHAO_PAYMENT_BASE_URL
LIUHAO_PAYMENT_PID
LIUHAO_PAYMENT_MERCHANT_PRIVATE_KEY_B64
LIUHAO_PAYMENT_PLATFORM_PUBLIC_KEY_B64
LIUHAO_PAYMENT_MERCHANT_PUBLIC_KEY_B64
LIUHAO_PAYMENT_NOTIFY_URL
LIUHAO_PAYMENT_RETURN_URL
LIUHAO_PAYMENT_CONNECT_TIMEOUT
LIUHAO_PAYMENT_READ_TIMEOUT
LIUHAO_PAYMENT_RESPONSE_MAX_BYTES
LIUHAO_PAYMENT_TIMESTAMP_TOLERANCE
```

商户私钥使用 PKCS#8，平台公钥和商户公钥使用 X.509。商户公钥只在启动期检查密钥配对；商户私钥负责 `SHA256WithRSA` 签名；平台公钥负责验证六号响应、查询和回调。三个密钥只能进入后端 Secret 管理，禁止进入前端、Cloudflare、日志、PostgreSQL 或 Redis。商户后台没有公开 `channel_id` 配置，本项目不得虚构或提交该字段。

生产公开配置示例：

```text
MEMBERSHIP_PAYMENT_ENABLED=true
MEMBERSHIP_PAYMENT_CHECKOUT_ENABLED=true
MEMBERSHIP_PAYMENT_DEFAULT_PROVIDER=LIUHAO
MEMBERSHIP_PAYMENT_PUBLIC_PROVIDERS=BAR,LIUHAO
LIUHAO_PAYMENT_ENABLED=true
BAR_PAYMENT_ENABLED=true
SIMULATED_PAYMENT_ENABLED=false
```

## HTTP 与回调

- 支付宝统一下单：后端调用 `/api/pay/create`，继续验证 JSON 签名、订单、渠道、金额和 HTTPS 支付入口
- 微信页面提交：后端只调用一次 `/api/pay/submit`，禁止自动重试和跟随重定向；只接受恰好一个 `302/303 Location`
- 微信交易确认：从同源 `/pay/qrcode/<候选交易号>/` 或 `/pay/jspay/<候选交易号>/` 路径提取候选值，再调用 `/api/pay/query` 验签确认本地订单、真实交易号和金额；查询完成前不持久化候选值
- H5 打开入口：只有已确认的同源 `/pay/qrcode/<真实交易号>/` 才执行浏览器顶层 GET 导航；`/pay/jspay/` 会在绑定已确认真实交易号后受控拒绝，微信 OAuth、其他 Host、额外路径、Query、Fragment 和未知载体均不会交给浏览器
- 订单查询：`/api/pay/query`
- 订单退款：`/api/pay/refund`
- 关闭订单：`/api/pay/close`
- 本项目回调：`GET /api/payment/liuhao/notify`

禁止由前端或后端把 `/pay/jspay/...` 字符串替换为 `/pay/qrcode/...`，也禁止直接拼接六号平台交易号生成页面地址。微信收银台 URL 只能取自后端捕获的第一跳 Location，并且必须经过同一真实交易号的签名查询确认；查询失败时保持“已发起但交易号为空”的不确定状态，不允许再次提交创建第二笔六号订单。

浏览器顶层 HTTPS 跳转不依赖 CORS；六号通知是服务器到服务器调用，也不依赖浏览器 CORS。回调只有在 RSA 验签、时间戳、商户号、订单、支付方式、金额、状态和主动查询全部通过后才返回纯文本 `success`。

支付返回页只轮询本项目订单状态，不信任 `return_url` 查询参数。`PENDING_PAYMENT -> CLOSING` 后仍立即首次关单；五分钟 `CLOSING` 窗口继续允许在途成功回调收敛到 `PAID`。

## 关单请求与上游拒绝诊断日志

六号关单由后端调用 `POST /api/pay/close`。请求签名完成并装入表单后，后端输出一条 `event=liuhao_request_signature` 结构化日志；该事件只记录字段存在性、`sign_type` 分类、固定的 `SHA256WithRSA` 算法和 `traceId`，不记录 PID、订单号、签名、密钥或待签名字符串。

响应仍由 `event=liuhao_response_verification` 记录。`verification_stage` 固定区分 `transport`、`response_size`、`json_shape`、`signature_metadata`、`signature_encoding`、`canonicalization`、`rsa_verification`、`timestamp_validation`、`business_code` 和 `complete`。`provider_code_numeric` 只允许记录 0 到 999999 的数字；`provider_code_trust=verified` 仅表示 RSA 与时间戳校验通过，签名失败时即使响应带有数字 `code` 也必须标记为 `untrusted`，不能据此把订单当作已关闭。

下一次排查关单时按以下顺序解释日志：

| 日志结果 | 结论 |
| --- | --- |
| 请求 `sign_type_present=false` 或 `sign_present=false` | 我方请求组装或签名步骤异常 |
| 请求签名字段完整，响应 `has_timestamp=false`、`has_sign=false`、`has_sign_type=false` | 六号返回了未签名响应，可能是上游业务拒绝或关闭接口未遵循响应签名合同 |
| 响应 `sign_type_class=unexpected` | 六号返回的签名类型不是严格的 `RSA` |
| 响应原因为 `PLATFORM_SIGNATURE_MISMATCH` | 平台公钥、响应字段规范化或六号生成的签名不匹配 |
| 响应验签通过且 `provider_code_numeric` 非 0 | 六号明确返回业务拒绝码，可结合六号错误码表解释 |
| HTTP 非 2xx、JSON 无法解析或超时 | 网关或传输层问题 |

未签名响应中的数字 `code` 仅作为诊断线索，不改变现有安全裁决；若六号没有提供错误码表或响应签名，具体拒绝原因仍需结合六号侧日志确认。`platform_id` 不是本项目关闭请求字段，`pid` 必须继续使用六号商户号。
