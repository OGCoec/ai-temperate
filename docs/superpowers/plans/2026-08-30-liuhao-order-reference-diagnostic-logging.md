# 六号订单引用诊断日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改数据库字段、订单状态机和六号协议的前提下，用生产可见且不泄密的结构化日志定位 `LIUHAO:ORDER:<商户订单号>` 为什么没有升级为 `LIUHAO:TRADE:<六号系统订单号>`，并明确关单为什么停留在 `CLOSING`。

**Architecture:** 日志分为三层：六号 HTTP 客户端记录请求定位方式与验签后的业务结果；支付检查和关单消费者记录平台流水绑定结果；关单消费者记录本地状态迁移决策。所有层使用现有 `MembershipPaymentDiagnosticId.orderRef(...)` 关联订单，只记录枚举、布尔值、受控错误码、`traceId` 和 `messageId`，不记录原始订单号、签名、密钥、Cookie 或完整响应。

**Tech Stack:** Java 21、Spring Boot、SLF4J/Logback、Spring `OutputCaptureExtension`、JUnit 5、Mockito、MockRestServiceServer。

---

## 文件范围

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
  - 记录六号查询/关单使用 `out_trade_no` 还是 `trade_no`。
  - 记录响应验签通过后的状态、系统流水是否存在以及受控失败码。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipPaymentCheckConsumerServiceImpl.java`
  - 记录 PENDING 最终查询返回的平台状态和系统流水绑定结果。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java`
  - 记录首次关单、补偿关单、平台流水绑定和 `CLOSING` 最终迁移结果。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`
  - 验证日志区分商户订单定位与系统流水定位，并且不泄露原始编号和签名。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`
  - 验证查询、关单、绑定、重试和终态日志。
- `docs/integrations/liuhao-payment-v2.md`
  - 固化订单号术语、预期日志序列和生产排查方法。

**明确不修改：**

- 不增加或修改 PostgreSQL 字段、约束、索引和迁移。
- 不修改 Redis Key、快照 Schema 或 Lua 状态机。
- 不修改 `PENDING_PAYMENT -> CLOSING -> CLOSED/PAID` 规则。
- 不修改六号签名字段、请求字段、成功码和接口地址。
- 不修改 RabbitMQ 消息结构、队列、Exchange 和延迟时间。
- 不修改前端、Cloudflare Pages、Cloudflare Worker。
- 不新增日志开关环境变量。

## 固定术语与日志安全合同

日志中固定使用以下含义，禁止只写容易混淆的 `ORDER` 或 `TRADE`：

```text
referenceKind=MERCHANT_ORDER
requestParameter=out_trade_no
```

表示项目生成的商户订单号；以及：

```text
referenceKind=PROVIDER_SYSTEM_TRADE
requestParameter=trade_no
```

表示六号生成的系统订单号。

日志允许字段：

```text
event
operation
orderRef
referenceKind
requestParameter
providerCode
providerStatus
systemTradeNoPresent
databaseBound
redisOutcome
fromStatus
toStatus
transitionOutcome
nextAction
stageIndex
terminalRetryCount
boundaryReached
traceId
messageId
errorCode
```

日志禁止字段：

```text
out_trade_no 原文
trade_no 原文
api_trade_no 原文
pid 原文
sign
商户私钥
平台公钥
完整请求表单
完整六号响应
Cookie
Access Token
Refresh Token
用户 ID
幂等键原文
```

---

### Task 1: 先固定六号 HTTP 日志契约测试

**Files:**

- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 给测试类启用日志捕获**

增加导入和类级扩展：

```java
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
final class LiuhaoPaymentRestClientImplTest {
```

- [ ] **Step 2: 编写使用商户订单号查询的日志测试**

复用现有签名响应夹具，增加：

```java
@Test
void queryByMerchantOrderLogsExplicitLocatorWithoutRawIdentifiers(
        CapturedOutput output) throws Exception {
    server.expect(requestTo(BASE_URL + "/api/pay/query"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                    json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                    MediaType.APPLICATION_JSON));

    client.queryPayment(new PaymentQueryCommand(ORDER_ID, null));

    assertThat(output.getOut())
            .contains("event=liuhao_request")
            .contains("operation=query")
            .contains("referenceKind=MERCHANT_ORDER")
            .contains("requestParameter=out_trade_no")
            .contains("event=liuhao_response_verified")
            .contains("providerCode=0")
            .contains("providerStatus=PENDING")
            .contains("systemTradeNoPresent=true")
            .doesNotContain(ORDER_ID)
            .doesNotContain(TRADE_NO)
            .doesNotContain("sign=");
}
```

- [ ] **Step 3: 编写使用六号系统流水关单的日志测试**

```java
@Test
void closeBySystemTradeLogsExplicitLocatorWithoutRawIdentifiers(
        CapturedOutput output) throws Exception {
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                    json(signed(closeResponse(0, ORDER_ID, TRADE_NO))),
                    MediaType.APPLICATION_JSON));

    client.closePayment(new PaymentCloseCommand(ORDER_ID, TRADE_NO));

    assertThat(output.getOut())
            .contains("operation=close")
            .contains("referenceKind=PROVIDER_SYSTEM_TRADE")
            .contains("requestParameter=trade_no")
            .contains("providerStatus=CLOSED")
            .contains("systemTradeNoPresent=true")
            .doesNotContain(ORDER_ID)
            .doesNotContain(TRADE_NO);
}
```

- [ ] **Step 4: 编写失败日志脱敏测试**

构造六号超时，断言只输出受控错误码：

```java
@Test
void closeFailureLogsControlledCodeWithoutProviderDetails(
        CapturedOutput output) {
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andRespond(withException(
                    new SocketTimeoutException("sensitive upstream detail")));

    assertThatThrownBy(() -> client.closePayment(
            new PaymentCloseCommand(ORDER_ID, null)))
            .isInstanceOf(MembershipPaymentException.class);

    assertThat(output.getOut())
            .contains("event=liuhao_request_failed")
            .contains("operation=close")
            .contains("errorCode=LIUHAO_TIMEOUT")
            .doesNotContain("sensitive upstream detail")
            .doesNotContain(ORDER_ID)
            .doesNotContain(TRADE_NO);
}
```

- [ ] **Step 5: 第二阶段授权后运行该测试类并确认先失败**

```powershell
mvn -pl ai-temperate-service -Dtest=LiuhaoPaymentRestClientImplTest test
```

预期：新增日志断言失败；既有 HTTP、验签和成功码断言仍保持原行为。

---

### Task 2: 在六号 HTTP 客户端记录请求定位方式和可信响应

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java:45-178`

- [ ] **Step 1: 增加日志依赖和 Logger**

```java
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentDiagnosticId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger LOGGER =
        LoggerFactory.getLogger(LiuhaoPaymentRestClientImpl.class);
```

- [ ] **Step 2: 增加仅供日志使用的受控术语方法**

方法只根据是否已有真实六号流水选择固定枚举字符串，不读取或返回原始编号：

```java
private static String referenceKind(String providerTradeNo) {
    return providerTradeNo == null || providerTradeNo.isBlank()
            ? "MERCHANT_ORDER"
            : "PROVIDER_SYSTEM_TRADE";
}

private static String requestParameter(String providerTradeNo) {
    return providerTradeNo == null || providerTradeNo.isBlank()
            ? "out_trade_no"
            : "trade_no";
}
```

- [ ] **Step 3: 在查询和关单发出前记录请求定位方式**

在 `queryPayment` 和 `closePayment` 中，调用 `postVerified` 前记录：

```java
String orderRef = MembershipPaymentDiagnosticId.orderRef(value.orderId());
LOGGER.info(
        "event=liuhao_request operation={} orderRef={} referenceKind={} requestParameter={}",
        "query",
        orderRef,
        referenceKind(value.providerTradeNo()),
        requestParameter(value.providerTradeNo()));
```

关单使用同一结构，`operation` 固定为 `close`。

- [ ] **Step 4: 只在验签和订单一致性校验完成后记录成功响应**

查询在 `requireSameOrder(...)` 和状态解析之后记录：

```java
LOGGER.info(
        "event=liuhao_response_verified operation={} orderRef={} "
                + "providerCode={} providerStatus={} systemTradeNoPresent={}",
        "query",
        orderRef,
        "0",
        status,
        tradeNo != null);
```

关单在 `validateOptionalIdentity(...)` 和状态解析之后记录同样字段。日志位置不得移动到验签、时间戳、`pid` 或订单一致性检查之前。

- [ ] **Step 5: 在查询和关单公开方法边界记录受控失败**

分别用 `try/catch` 包住现有方法主体；只记录错误码，不记录异常消息和堆栈中的上游正文：

```java
} catch (MembershipPaymentException exception) {
    LOGGER.warn(
            "event=liuhao_request_failed operation={} orderRef={} "
                    + "referenceKind={} requestParameter={} errorCode={}",
            "close",
            orderRef,
            referenceKind(value.providerTradeNo()),
            requestParameter(value.providerTradeNo()),
            exception.code().name());
    throw exception;
}
```

不得在 `postVerified` 内记录 `request` Map、`response` byte array 或解析后的 `body` Map。

- [ ] **Step 6: 第二阶段授权后运行 HTTP 客户端测试**

```powershell
mvn -pl ai-temperate-service -Dtest=LiuhaoPaymentRestClientImplTest test
```

预期：PASS，日志能区分 `out_trade_no` 与 `trade_no`，并且不包含测试中的原始订单号、系统流水和敏感异常正文。

- [ ] **Step 7: 创建独立提交**

```powershell
git add ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java
git commit -m "chore: trace liuhao payment references safely"
```

---

### Task 3: 固定消费者查询、绑定和终态日志测试

**Files:**

- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`

- [ ] **Step 1: 扩展现有 PENDING 查询绑定测试**

现有测试已经覆盖 `LIUHAO:ORDER -> LIUHAO:TRADE` 的数据库与 Redis 写回；在该测试增加 `CapturedOutput` 参数并断言：

```java
assertThat(output.getOut())
        .contains("event=membership_payment_provider_result")
        .contains("operation=query")
        .contains("referenceKind=MERCHANT_ORDER")
        .contains("providerStatus=PENDING")
        .contains("systemTradeNoPresent=true")
        .contains("event=membership_payment_provider_trade_binding")
        .contains("fromKind=MERCHANT_ORDER")
        .contains("toKind=PROVIDER_SYSTEM_TRADE")
        .contains("databaseBound=true")
        .contains("redisOutcome=APPLIED")
        .doesNotContain(LIUHAO_ORDER_REFERENCE)
        .doesNotContain(LIUHAO_TRADE_REFERENCE);
```

- [ ] **Step 2: 增加首次关单成功但仍等待五分钟边界的日志测试**

使用 `CLOSING`、截止时间在未来、Provider 返回 `CLOSED` 和真实六号流水，断言：

```text
event=membership_closing_provider_result
providerStatus=CLOSED
systemTradeNoPresent=true
event=membership_payment_provider_trade_binding
databaseBound=true
redisOutcome=APPLIED
event=membership_closing_decision
boundaryReached=false
nextAction=SCHEDULE_FINAL_BOUNDARY
```

同时继续断言 `finalizeClosing` 未调用，证明日志没有改变五分钟回调窗口。

- [ ] **Step 3: 增加最终边界状态迁移日志测试**

使用截止时间已到、Provider 返回 `CLOSED`、`finalizeClosing` 返回 `APPLIED`，断言：

```text
event=membership_closing_transition
fromStatus=CLOSING
toStatus=CLOSED
transitionOutcome=APPLIED
boundaryReached=true
```

- [ ] **Step 4: 增加 UNKNOWN 与绑定冲突日志测试**

分别固定：

- Provider 调用失败：只输出 `errorCode=LIUHAO_TIMEOUT` 或异常类名，不输出异常消息。
- Redis 返回 `CONFLICT`：输出 `event=membership_payment_provider_trade_binding_failed` 和 `errorCode=MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT`。
- Provider 没有返回系统流水：输出 `systemTradeNoPresent=false`，并且不得谎报 `databaseBound=true`。

- [ ] **Step 5: 第二阶段授权后运行消费者测试并确认日志断言先失败**

```powershell
mvn -pl ai-temperate-service -Dtest=MembershipPaymentCheckConsumerServiceImplTest test
```

预期：新增日志断言失败；既有状态机、调度和幂等断言不应改变。

---

### Task 4: 在 PENDING 最终查询处记录平台流水升级

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipPaymentCheckConsumerServiceImpl.java:145-190`

- [ ] **Step 1: 使用现有诊断摘要生成 `orderRef`**

增加导入：

```java
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentDiagnosticId;
```

在查询成功后记录：

```java
LOGGER.info(
        "event=membership_payment_provider_result operation=query orderRef={} "
                + "referenceKind={} providerStatus={} systemTradeNoPresent={} "
                + "traceId={} messageId={}",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        PaymentProviderReference.pending(order.providerTradeNo())
                ? "MERCHANT_ORDER"
                : "PROVIDER_SYSTEM_TRADE",
        result.status(),
        result.providerTradeNo() != null
                && !PaymentProviderReference.pending(result.providerTradeNo()),
        traceId,
        messageId);
```

- [ ] **Step 2: 在真实流水写入成功后记录数据库和 Redis 结果**

保留现有绑定顺序：先数据库、后 Redis。两步均成功后记录：

```java
LOGGER.info(
        "event=membership_payment_provider_trade_binding orderRef={} "
                + "fromKind=MERCHANT_ORDER toKind=PROVIDER_SYSTEM_TRADE "
                + "databaseBound=true redisOutcome={}",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        outcome);
```

- [ ] **Step 3: 记录缺少系统流水和冲突的明确原因**

只有当前引用仍是六号 `ORDER` 时才记录缺失告警，避免 BAR 或已经绑定的订单产生噪声：

```java
LOGGER.warn(
        "event=membership_payment_provider_trade_binding_skipped "
                + "orderRef={} reason=MISSING_SYSTEM_TRADE",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()));
```

Redis `CONFLICT` 在抛出现有受控异常前记录：

```java
LOGGER.warn(
        "event=membership_payment_provider_trade_binding_failed "
                + "orderRef={} errorCode={}",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT);
```

- [ ] **Step 4: 第二阶段授权后运行消费者测试**

```powershell
mvn -pl ai-temperate-service -Dtest=MembershipPaymentCheckConsumerServiceImplTest test
```

预期：PENDING 查询路径的返回状态和 `ORDER -> TRADE` 写回结果可以从同一 `orderRef` 串联。

---

### Task 5: 在 CLOSING 关单链记录平台结果、调度决策和终态迁移

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:97-295`

- [ ] **Step 1: 记录每次关单返回的可信业务结果**

在 `closeOrUnknown` 成功返回并完成流水绑定后记录：

```java
LOGGER.info(
        "event=membership_closing_provider_result operation=close orderRef={} "
                + "referenceKind={} providerStatus={} systemTradeNoPresent={} "
                + "traceId={} messageId={}",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        PaymentProviderReference.pending(order.providerTradeNo())
                ? "MERCHANT_ORDER"
                : "PROVIDER_SYSTEM_TRADE",
        result.status(),
        result.providerTradeNo() != null
                && !PaymentProviderReference.pending(result.providerTradeNo()),
        traceId,
        messageId);
```

- [ ] **Step 2: 复用 Task 4 的绑定日志合同**

`bindResolvedProviderTradeNo` 继续执行：

```text
数据库绑定
-> Redis patch
-> 记录 databaseBound=true 与实际 redisOutcome
```

不得为了日志改变现有异常传播、幂等判断或写入顺序。

- [ ] **Step 3: 在每个关单决策出口记录唯一的 `nextAction`**

固定动作枚举：

```text
STOP_FOR_CALLBACK
RETRY_MISSING_DEADLINE
QUERY_PAID_FACT
PUBLISH_NEXT_STAGE
SCHEDULE_FINAL_BOUNDARY
RETRY_TERMINAL
FINALIZE_CLOSED
```

首次安全关单但尚未到截止时间时记录：

```java
LOGGER.info(
        "event=membership_closing_decision orderRef={} providerStatus={} "
                + "boundaryReached=false nextAction=SCHEDULE_FINAL_BOUNDARY "
                + "stageIndex={} terminalRetryCount={}",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        close.status(),
        message.stageIndex(),
        message.terminalRetryCount());
```

该日志必须位于 `scheduleClosing(...)` 之前，但不得提前执行 `finalizeClosing(...)`。

- [ ] **Step 4: 记录最终 Lua 迁移结果**

`finalizeClosing(...)` 返回后记录：

```java
LOGGER.info(
        "event=membership_closing_transition orderRef={} "
                + "fromStatus=CLOSING toStatus={} transitionOutcome={} "
                + "boundaryReached=true",
        MembershipPaymentDiagnosticId.orderRef(order.orderId()),
        transition.outcome() == MembershipOrderTransitionOutcome.APPLIED
                ? "CLOSED"
                : "UNCHANGED",
        transition.outcome());
```

如果结果是 `CALLBACK_IN_PROGRESS`、`TOO_EARLY`、`PROVIDER_STATUS_UNSAFE` 或 `MISSING`，日志必须保留真实 `transitionOutcome`，不能打印成已关闭。

- [ ] **Step 5: 第二阶段授权后运行消费者测试**

```powershell
mvn -pl ai-temperate-service -Dtest=MembershipPaymentCheckConsumerServiceImplTest test
```

预期：测试继续证明安全关单后不会提前从 `CLOSING` 变为 `CLOSED`，同时日志能解释每次调度和最终结果。

- [ ] **Step 6: 创建独立提交**

```powershell
git add ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipPaymentCheckConsumerServiceImpl.java ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java
git commit -m "chore: trace membership closing decisions"
```

---

### Task 6: 文档、脱敏检查和生产验证

**Files:**

- Modify: `docs/integrations/liuhao-payment-v2.md`

- [ ] **Step 1: 在接入文档增加明确的订单号映射**

加入：

```text
项目公共订单号 -> 六号 out_trade_no -> 商户订单号
六号 trade_no -> 六号系统订单号

LIUHAO:ORDER:<项目公共订单号>
    表示系统流水尚未可信取得；调用查询/关单时使用 out_trade_no。

LIUHAO:TRADE:<六号 trade_no>
    表示系统流水已验签并绑定；调用查询/关单时优先使用 trade_no。
```

- [ ] **Step 2: 写入预期生产日志序列**

一笔未支付订单的正常序列必须是：

```text
liuhao_request operation=query referenceKind=MERCHANT_ORDER requestParameter=out_trade_no
liuhao_response_verified providerCode=0 providerStatus=PENDING systemTradeNoPresent=true
membership_payment_provider_trade_binding fromKind=MERCHANT_ORDER toKind=PROVIDER_SYSTEM_TRADE
liuhao_request operation=close referenceKind=PROVIDER_SYSTEM_TRADE requestParameter=trade_no
liuhao_response_verified providerCode=0 providerStatus=CLOSED
membership_closing_decision boundaryReached=false nextAction=SCHEDULE_FINAL_BOUNDARY
membership_closing_transition fromStatus=CLOSING toStatus=CLOSED transitionOutcome=APPLIED
```

- [ ] **Step 3: 第二阶段授权后执行静态泄密扫描**

```powershell
rg -n "LOGGER\.|log\." ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl
```

逐条确认日志参数中不存在 `sign`、私钥、公钥、完整请求/响应、原始 `out_trade_no`、原始 `trade_no`、Cookie、Token 和用户 ID。

- [ ] **Step 4: 第二阶段授权后运行两类定向测试**

```powershell
mvn -pl ai-temperate-service -Dtest=LiuhaoPaymentRestClientImplTest,MembershipPaymentCheckConsumerServiceImplTest test
```

预期：BUILD SUCCESS；日志契约、状态机和脱敏断言全部通过。

- [ ] **Step 5: 部署范围确认**

本次日志位于 Java 后端：

```text
需要：重新构建并部署 ai-temperate-web 可执行后端
不需要：重新部署 Cloudflare Pages
不需要：重新部署 Cloudflare Worker
不需要：执行数据库迁移
不需要：新增环境变量
```

- [ ] **Step 6: 使用一笔受控未支付六号订单验证**

验证顺序：

1. 创建六号订单并跳转收银台，但不付款。
2. 创建后立即确认数据库允许暂存 `LIUHAO:ORDER:<商户订单号>`。
3. 等待进入 `CLOSING`，按同一 `orderRef` 搜索全部日志。
4. 确认查询使用 `out_trade_no`，可信响应包含系统流水。
5. 确认绑定日志显示数据库与 Redis 成功，并观察数据库变成 `LIUHAO:TRADE:<六号系统订单号>`。
6. 确认首次关单后本地仍保持 `CLOSING`，日志显示等待最终边界。
7. 最终边界后确认迁移结果为 `APPLIED`，数据库状态变为 `CLOSED`。
8. 如果任一步失败，根据最后一个成功事件确定故障边界，停止猜测，不直接修改数据库状态。

- [ ] **Step 7: 创建文档提交**

```powershell
git add docs/integrations/liuhao-payment-v2.md
git commit -m "docs: document liuhao payment diagnostics"
```

## 验收标准

- 新订单刚创建时，日志明确说明 `LIUHAO:ORDER` 对应商户 `out_trade_no`，不会被描述为六号系统单号。
- 查询或关单获得可信 `trade_no` 后，日志能证明数据库和 Redis 是否完成 `ORDER -> TRADE`。
- 每次关单都能看见六号返回状态、本地边界判断和下一步动作。
- 最终 `CLOSING -> CLOSED` 能看见真实 Lua 迁移结果；未迁移时日志不能谎报成功。
- 任意日志中均不存在原始商户订单号、六号系统订单号、签名、密钥、Token、Cookie 或完整响应。
- 不修改数据库、Redis Schema、状态机、Provider 合同、RabbitMQ、前端和 Cloudflare。
- 日志代码上线只要求重新部署 Java 后端。

## 回滚方式

日志不参与订单状态裁决。若生产日志量超出预期，按两个独立提交逆序回滚；回滚不需要数据库、Redis、RabbitMQ、Cloudflare 或前端操作，也不会改变已有订单状态。
