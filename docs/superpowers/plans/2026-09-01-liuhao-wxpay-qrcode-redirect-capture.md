# Liuhao Wxpay QR Redirect Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Do not use subagents unless the user explicitly authorizes them. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 六号微信只创建一笔外部订单，由后端截获六号页面提交响应中的二维码重定向、验真并绑定真实交易号，然后让现有前端顶层跳转到 `/pay/qrcode/<trade_no>/`。

**Architecture:** 支付宝继续使用 `/api/pay/create` 的签名 JSON 创建流程；微信改为由后端向 `/api/pay/submit` 提交一次签名表单，并依赖现有禁止自动重定向的 `RestClient` 捕获 302/303 `Location`。后端从严格校验后的二维码路径提取候选交易号，再调用已存在的签名查询接口确认本地订单号、真实交易号和金额，完成 PostgreSQL 与 Redis 绑定后返回现有 `REDIRECT_URL + GET` 描述。任何不确定结果都保留 `payment_started_at != NULL, provider_trade_no = NULL`，不重试创建、不生成 `LIUHAO:ORDER:`。

**Tech Stack:** Java 21、Spring Boot `RestClient`、Apache HttpComponents（禁止自动重定向与自动重放）、PostgreSQL、Redis、JUnit 5、Mockito、Spring `MockRestServiceServer`、Vue/UniApp 现有支付导航合同。

---

## 0. 已确认事实与实施闸门

- 六号商户端不存在可配置的 `channel_id`，公开支付方式仅使用 `wxpay`/`alipay`。
- 当前 `liuhaoPaymentRestClient` 已同时配置 `Redirects.DONT_FOLLOW` 和 `disableRedirectHandling()`，可以直接用于捕获首跳响应。
- `/pay/qrcode/<trade_no>/` 是已创建订单的收银台页面，不是订单创建接口；禁止使用本地订单 ID 拼接路径。
- 同一笔微信支付只能调用一次创建入口；禁止先调用 `/api/pay/create` 再调用 `/api/pay/submit`。
- 本计划的外部事实假设只有一个：后端 POST `/api/pay/submit` 时，六号会返回一个包含 `Location` 的 302 或 303。该假设必须先由 Mock 测试固定合同，发布前再经用户明确授权用一笔新低金额订单验证。
- 如果现场返回 200 HTML、缺失 `Location`，或始终返回 `/pay/jspay/`，立即停止发布；不得解析 HTML、伪造 `/qrcode/` 路径或重新创建第二笔订单。

## 1. 文件结构与职责

### 修改文件

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentProperties.java`
  - 删除未经证实的 `wxpayQrcodeChannelId` 字段、正则和启动校验。
- `ai-temperate-web/src/main/resources/application.yml`
  - 删除 `wxpay-qrcode-channel-id` 配置及注释，保持每个 YAML 配置行前一行中文注释。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
  - 微信走页面提交重定向捕获；支付宝保留 JSON 创建；负责 Location 校验、交易号提取和签名查询验真。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoMembershipPaymentProviderImpl.java`
  - 仅更新中文 JavaDoc，明确微信创建结果来自重定向捕获且统一添加 `LIUHAO:TRADE:` 前缀。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentAttemptServiceImpl.java`
  - 保持“先绑定真实交易号，再返回浏览器入口”，更新六号分支注释和不确定结果约束。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java`
  - 增加脱敏的页面提交重定向诊断事件。
- `ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/MembershipPaymentExceptionHandler.java`
  - 保持现有错误结构，确认页面提交结果未知使用稳定的中文提示且不触发取消。

### 测试文件

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentPropertiesTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentAttemptServiceImplTest.java`
- `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/MembershipOrderWebContractTest.java`
- `fornted/common/user/membership-payment-state.test.cjs`
- `fornted/pages/account/membership-payment-pages-contract.test.cjs`

### 不修改

- 不修改数据库表、约束或迁移 SQL。
- 不修改 Redis Key/Value 结构。
- 不修改 RabbitMQ 消息结构。
- 不修改 `/payment-attempts` 的请求和响应 JSON。
- 不修改 Cloudflare Worker、Pages 或前端生产资源。

## 2. 任务一：删除虚构的微信通道 ID

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentProperties.java`
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentPropertiesTest.java`

- [ ] **Step 1: 先改配置测试，证明六号启用不再要求通道 ID**

将 `enabledLiuhaoRequiresConfiguredWxpayQrcodeChannel()` 和 `disabledLiuhaoDoesNotRequireWxpayQrcodeChannel()` 替换为以下合同：

```java
@Test
void enabledLiuhaoDoesNotRequireAnUndocumentedChannelId() {
    assertThatCode(() -> validPropertiesWithLiuhao(true))
            .doesNotThrowAnyException();
}
```

测试构造器不得再接受 `wxpayQrcodeChannelId` 参数。

- [ ] **Step 2: 删除属性与启动校验**

从 `MembershipPaymentProperties.Liuhao` 删除：

```java
@DefaultValue("") String wxpayQrcodeChannelId,
```

删除 `LIUHAO_WXPAY_QRCODE_CHANNEL_ID` 以及 `validateLiuhao()` 中对应的异常分支。同步更新所有 `new Liuhao(...)` 的参数顺序。

- [ ] **Step 3: 删除 YAML 配置**

完整删除以下两行，不保留空配置或猜测默认值：

```yaml
# 六号微信 Native/PC 扫码通道 ID只从环境变量读取，缺失时禁止启动真实六号支付。
wxpay-qrcode-channel-id: ${LIUHAO_PAYMENT_WXPAY_QRCODE_CHANNEL_ID:}
```

- [ ] **Step 4: 检查源码中不再存在该字段**

计划验证命令：

```powershell
rg -n "wxpayQrcodeChannelId|wxpay-qrcode-channel-id|LIUHAO_PAYMENT_WXPAY_QRCODE_CHANNEL_ID|channel_id" ai-temperate-service ai-temperate-web
```

期望：生产代码零匹配；测试夹具也不得保留虚构值。

## 3. 任务二：为页面提交增加窄化的重定向响应模型

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 写 302 捕获失败测试**

测试必须模拟：

```http
HTTP/1.1 302 Found
Location: https://liuhao.net/pay/qrcode/202609011234567890/
```

并断言微信请求只命中 `/api/pay/submit`，不会命中 `/api/pay/create`；在实现前测试应失败。

- [ ] **Step 2: 在实现类内部增加只描述响应元数据的 record**

```java
private record LiuhaoSubmitRedirectResponse(
        int status,
        java.util.List<String> locations,
        String contentTypeClass) {

    private LiuhaoSubmitRedirectResponse {
        locations = java.util.List.copyOf(locations);
    }
}
```

该值不得包含 HTML 正文、Cookie、签名或完整请求字段。

- [ ] **Step 3: 增加页面提交方法**

新增私有方法签名：

```java
private LiuhaoSubmitRedirectResponse postCheckoutWithoutRedirect(
        Map<String, String> signedRequest)
```

实现约束：

```java
LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
signedRequest.forEach(form::add);

return restClient.post()
        .uri(SUBMIT_PATH)
        .contentType(FORM_UTF8)
        .body(form)
        .exchange((outbound, upstream) -> new LiuhaoSubmitRedirectResponse(
                upstream.getStatusCode().value(),
                upstream.getHeaders().getOrEmpty(org.springframework.http.HttpHeaders.LOCATION),
                contentTypeClass(upstream.getHeaders().getContentType())));
```

不得设置 `Accept: application/json`，不得读取或解析 HTML，且继续复用已禁止自动重定向和自动重放的专用 `RestClient`。

- [ ] **Step 4: 明确传输异常语义**

进入 `restClient.post()` 后发生超时、连接中断或空响应，一律转换为：

```java
new MembershipPaymentException(
        MembershipPaymentErrorCode.LIUHAO_CREATE_OUTCOME_UNKNOWN,
        "Liuhao checkout submission outcome is still being confirmed.")
```

异常不携带猜测的交易号；业务层必须保留 `payment_started_at`，禁止再次创建。

## 4. 任务三：严格验证 `/pay/qrcode/<trade_no>/` Location

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 增加 Location 分类与提取测试**

分别覆盖：

```text
302 + 绝对 /pay/qrcode/<trade>/          -> 候选成功
303 + 根相对 /pay/qrcode/<trade>/        -> 候选成功
302 + /pay/jspay/<trade>/                -> 受控拒绝但保留候选交易号
301/307/308                              -> 拒绝
零个或多个 Location                     -> 结果未知
其他 Host、HTTP、非默认端口、UserInfo    -> 拒绝
Query、Fragment、额外路径、编码斜杠       -> 拒绝
空交易号、超长值、控制字符               -> 拒绝
```

- [ ] **Step 2: 增加重定向解析结果**

```java
private record LiuhaoCheckoutRoute(
        URI action,
        String tradeNo,
        String routeKind) {
}
```

`routeKind` 只允许 `qrcode_page_url` 或 `jspay_page_url`，用于低基数日志。

- [ ] **Step 3: 实现严格解析方法**

```java
private LiuhaoCheckoutRoute requireCheckoutRoute(
        LiuhaoSubmitRedirectResponse response)
```

必须执行以下顺序：

1. 状态只能为 302 或 303。
2. `Location` 必须恰好一个。
3. 只允许绝对 HTTPS URL或以 `/` 开头的根相对地址；相对地址只使用 `properties.baseUrl().resolve(...)`。
4. 最终 Scheme、Host、端口必须与 `baseUrl` 完全一致。
5. 禁止 UserInfo、Query、Fragment、控制字符和超过 4096 字符。
6. 使用 `getRawPath()`，只接受 `/pay/qrcode/<trade>/` 或 `/pay/jspay/<trade>/`。
7. 交易号通过现有 `SAFE_REFERENCE` 和 112 字符上限校验，禁止百分号编码。

不得用字符串替换把 `jspay` 改成 `qrcode`。

## 5. 任务四：微信提交后立即查询验真

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 写“提交 + 查询”双请求合同测试**

测试顺序必须为：

```text
POST /api/pay/submit -> 302 /pay/qrcode/<trade>/
POST /api/pay/query  -> 已验签 JSON，out_trade_no/trade_no/money 匹配
```

期望结果：

```java
new PaymentCreateResult(
        "202609011234567890",
        "qrcode",
        "https://liuhao.net/pay/qrcode/202609011234567890/",
        true)
```

- [ ] **Step 2: 将 `createPayment` 分成微信与支付宝两个私有分支**

```java
@Override
public PaymentCreateResult createPayment(PaymentCreateCommand command) {
    PaymentCreateCommand value = Objects.requireNonNull(command);
    PaymentCheckoutCommand checkout = requireCheckout(new PaymentCheckoutCommand(
            value.orderId(), value.amountYuan(), value.payType(), value.orderName()));
    return "wxpay".equals(checkout.payType())
            ? createWxpayFromSubmitRedirect(checkout)
            : createAlipayFromJsonApi(value, checkout);
}
```

`createAlipayFromJsonApi` 保留现有 `/api/pay/create`、`method=web`、`device=pc`、JSON 验签与 HTTPS 跳转逻辑，但彻底删除 `channel_id`。

- [ ] **Step 3: 实现微信创建分支**

```java
private PaymentCreateResult createWxpayFromSubmitRedirect(
        PaymentCheckoutCommand checkout) {
    Map<String, String> signed = signatures.sign(checkoutFields(checkout));
    LiuhaoCheckoutRoute route = requireCheckoutRoute(
            postCheckoutWithoutRedirect(signed));
    PaymentQueryResult verified = queryPayment(new PaymentQueryCommand(
            checkout.orderId(), route.tradeNo()));
    requireVerifiedCheckoutQuery(checkout, route, verified);
    if (!"qrcode_page_url".equals(route.routeKind())) {
        throw checkoutUnavailableAfterCreation(
                "Liuhao created a WeChat order but selected the JSAPI checkout route.",
                route.tradeNo());
    }
    return new PaymentCreateResult(
            route.tradeNo(), "qrcode", route.action().toString(), true);
}
```

- [ ] **Step 4: 验证查询结果与创建意图一致**

新增：

```java
private static void requireVerifiedCheckoutQuery(
        PaymentCheckoutCommand checkout,
        LiuhaoCheckoutRoute route,
        PaymentQueryResult verified)
```

约束：

- `verified.orderId()` 必须等于 `checkout.orderId()`。
- `verified.providerTradeNo()` 必须等于 `route.tradeNo()`。
- `verified.amountYuan()` 非空时必须等于 `checkout.amountYuan()`。
- 状态只允许 `PENDING` 或 `PAID`；`UNKNOWN`、`CLOSED`、`REFUNDED` 等不得返回支付入口。
- 查询响应继续通过现有 `postVerified()` 完成平台公钥验签、时间戳、商户号和订单归属验证。

- [ ] **Step 5: 定义失败后的交易号处理**

- 已通过签名查询确认的 `/jspay/`：抛 `LIUHAO_CHECKOUT_UNAVAILABLE` 并携带真实交易号，业务层先绑定后返回 409。
- Location 存在但查询超时、验签失败或归属不一致：不得把候选值当作真实交易号绑定；抛对应受控错误且不返回 URL。
- 请求结果未知且无可信交易号：保持 `started + trade null`，由既有按 `out_trade_no` 的发现流程查询六号。
- 任何失败均不得再次调用 `/api/pay/submit` 或 `/api/pay/create`。

## 6. 任务五：保持业务层“绑定后跳转”原子顺序

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentAttemptServiceImpl.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoMembershipPaymentProviderImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentAttemptServiceImplTest.java`

- [ ] **Step 1: 更新服务测试中的六号成功来源**

保留现有 `PaymentCreateResult` 合同，但测试名和断言必须表达：客户端已经完成 submit Location 捕获与查询验真，业务层只负责绑定。

- [ ] **Step 2: 保持成功写入顺序不变**

```text
provider.createPayment()
→ bindProviderReference() PostgreSQL
→ patchProviderTradeNo() Redis
→ 重新读取 Redis Guard
→ 构造 REDIRECT_URL + GET
→ 返回 payment-attempts 201
```

最终返回：

```java
new PaymentCheckoutSubmission(
        PaymentProviderType.LIUHAO,
        PaymentCheckoutMode.REDIRECT_URL,
        URI.create(created.payInfo()),
        "GET",
        null,
        guard.expiresAt(),
        null)
```

- [ ] **Step 3: 覆盖受控失败**

测试必须断言：

- 已验真的 JSPay 交易号仍执行 `NULL -> LIUHAO:TRADE:<trade_no>`。
- JSPay 不返回 `checkoutSubmission`，不调用 `closePayment()`，不创建第二笔订单。
- 提交结果未知且没有真实交易号时，数据库和 Redis 均保持空交易号，但 `payment_started_at` 保留。
- 重放请求返回 `PAYMENT_CREATE_OUTCOME_UNKNOWN`，且 `createPayment()` 不会第二次调用。
- 回调或发现流程之后仍可执行 `NULL -> LIUHAO:TRADE:<trade_no>`。

- [ ] **Step 4: 更新 JavaDoc**

将“六号必须由后端统一 JSON 下单”改为“六号微信由后端提交页面请求并截获受控重定向，支付宝由后端 JSON 创建；两者都必须先验真并绑定真实流水，浏览器只接收最终安全入口”。

## 7. 任务六：增加不泄密的结构化日志

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 增加诊断方法**

```java
public static void liuhaoSubmitRedirectValidation(
        String requestedChannel,
        String httpStatusClass,
        String locationCountClass,
        String routeKind,
        boolean tradeNoPresent,
        String queryOutcome,
        String outcome,
        String reason,
        String traceId)
```

日志格式：

```ini
event=liuhao_submit_redirect_validation
requested_channel=wxpay
http_status_class=3xx
location_count_class=one
route_kind=qrcode_page_url
trade_no_present=true
query_outcome=verified
outcome=accepted
reason=VALIDATED
traceId=...
```

- [ ] **Step 2: 拒绝路径分类**

至少包含：

```text
HTTP_STATUS_UNEXPECTED
LOCATION_MISSING
LOCATION_MULTIPLE
LOCATION_UNSAFE
JSPAY_ROUTE_UNEXPECTED
QUERY_UNVERIFIED
QUERY_ORDER_MISMATCH
QUERY_TRADE_MISMATCH
QUERY_AMOUNT_MISMATCH
```

- [ ] **Step 3: 日志泄密测试**

断言日志不包含：

- 完整 Location。
- 真实 `trade_no` 或 `out_trade_no`。
- MD5/RSA 密钥、签名。
- 表单正文、HTML 正文。
- Cookie、客户端 Token、`channel_id`。

## 8. 任务七：Web 与前端合同保持不变

**Files:**
- Test: `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/MembershipOrderWebContractTest.java`
- Test: `fornted/common/user/membership-payment-state.test.cjs`
- Test: `fornted/pages/account/membership-payment-pages-contract.test.cjs`

- [ ] **Step 1: Web 响应合同**

成功响应仍必须是：

```json
{
  "checkoutSubmission": {
    "provider": "LIUHAO",
    "checkoutMode": "REDIRECT_URL",
    "action": "https://liuhao.net/pay/qrcode/<真实交易号>/",
    "method": "GET",
    "contentType": null,
    "fields": null
  }
}
```

公开结构不新增 `tradeNo` 或 `channelId` 字段。

- [ ] **Step 2: 前端导航合同**

现有 `submitPaymentCheckout()` 对 `REDIRECT_URL + GET` 继续执行浏览器顶层导航，不创建 Form，不持久化 URL。

- [ ] **Step 3: 错误分流合同**

- `LIUHAO_CHECKOUT_UNAVAILABLE`：提示已创建但入口不可用，不调用 `/cancel`。
- `LIUHAO_CREATE_OUTCOME_UNKNOWN`：提示结果确认中，保留幂等键，不调用 `/cancel`。
- 只有 `LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE` 才允许用户确认后取消。

如果现有测试已经覆盖且无需修改源码，只增加断言；不要为了本次后端修复重新构建前端资源。

## 9. 任务八：验证顺序与现场闸门

按照项目规范，以下命令只在用户当前任务明确授权后执行。

- [ ] **Step 1: 后端定向测试**

```powershell
mvn -pl ai-temperate-service -am "-Dtest=MembershipPaymentPropertiesTest,LiuhaoPaymentRestClientImplTest,MembershipPaymentAttemptServiceImplTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

期望：所有定向测试通过，测试请求序列证明同一微信订单只调用一次 `/api/pay/submit`。

- [ ] **Step 2: Web 合同测试**

```powershell
mvn -pl ai-temperate-web -am "-Dtest=MembershipOrderWebContractTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: 前端合同测试**

```powershell
Set-Location 'C:\Users\damn\Desktop\ai-temperate-main\fornted'
node --test common/user/membership-payment-state.test.cjs pages/account/membership-payment-pages-contract.test.cjs
```

- [ ] **Step 4: 低金额外部验证（必须再次得到用户授权）**

只创建一笔全新的微信订单，观察后端日志和浏览器 Network：

```text
后端 POST /api/pay/submit
六号返回 302/303 + 单个 /pay/qrcode/<trade>/ Location
后端 POST /api/pay/query 并验签成功
数据库写入 LIUHAO:TRADE:<trade>
payment-attempts 返回 201
浏览器首个六号页面为 /pay/qrcode/<trade>/
页面 200 且显示二维码
```

数据库必须同时满足：

```sql
provider_trade_no LIKE 'LIUHAO:TRADE:%'
provider_trade_no NOT LIKE 'LIUHAO:ORDER:%'
```

- [ ] **Step 5: 失败即停止的条件**

出现以下任一项时停止部署，不做第二笔试单：

- `/api/pay/submit` 返回 200 HTML而不是 302/303。
- `Location` 为 `/pay/jspay/`。
- 查询接口无法按 `out_trade_no`/`trade_no`确认同一订单。
- 交易号、金额或订单号不一致。
- 前端出现 `/cancel`。

失败后的订单保留真实交易号或 `started + trade null`，交由回调/查询/关单流程收敛。

## 10. 发布顺序

1. 先提交代码与测试，不运行、不构建、不部署。
2. 用户授权后执行定向测试。
3. 用户授权后在非生产或受控环境用一笔低金额订单验证 302 Location 合同。
4. 只有验证得到 `/pay/qrcode/` 才部署后端。
5. 本次不构建、不上传 Cloudflare Pages，也不重新部署 Worker，因为前端 API 合同和资源没有变化。
6. 验收六号微信后再回归六号支付宝和 BAR，确认二者创建路径未变化。

## 11. 自检结论

- 同一微信订单只有一次创建请求：已覆盖。
- 前端跳转前绑定真实交易号：已覆盖。
- 不使用虚构 `channel_id`：已覆盖。
- 不将 JSPay 伪造成 QRCode：已覆盖。
- 不保存支付 URL、签名或 HTML：已覆盖。
- 结果未知不重试创建：已覆盖。
- 支付宝、BAR、回调、查询、关单边界不改变：已覆盖。
- 不需要数据库、Redis、MQ、前端或 Worker 结构变化：已覆盖。
