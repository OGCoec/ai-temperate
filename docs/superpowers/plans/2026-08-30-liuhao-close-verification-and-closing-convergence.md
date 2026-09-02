# 六号关单验签诊断与 CLOSING 最终收敛 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不绕过六号 RSA 响应验签、不新增数据库字段或 RabbitMQ 拓扑的前提下，精确定位关单响应的验签失败层，并让 Provider `UNKNOWN` 复用现有 `CLOSING` 时间链，在 `closingDeadlineAt` 到达后安全、幂等地收敛为本地 `CLOSED`。

**Architecture:** 第一条纵向链在六号 HTTP 客户端边界增加结构化验签结果和单条脱敏诊断事件，把当前合并的 `LIUHAO_SIGNATURE_INVALID` 拆成可行动的固定原因。第二条纵向链把“关单尝试”和“最终事实查询”解耦，并通过带有 `PROVIDER_CONFIRMED` / `TIMEOUT_UNCONFIRMED` 来源的 Redis Lua/CAS 执行唯一终态裁决；回调 marker 和并发终态始终优先。

**Tech Stack:** Java 21、Spring Boot、Spring `RestClient`、Jackson、Java `Signature` (`SHA256WithRSA`)、Redis、Lua、RabbitMQ、JUnit 5、Mockito、AssertJ。

---

## 0. 实施边界与当前事实

### 0.1 本计划不改变的边界

- 不新增或修改 PostgreSQL 表、字段、索引与迁移。
- 不新增 RabbitMQ Exchange、Queue、Routing Key 或消息类型。
- 不改变现有 `payment-check-delays-millis` 和 `closing-check-delays-millis`，仍保留五分钟待支付窗口与五分钟 `CLOSING` 回调窗口。
- 不增加本地 `UNKNOWN` 订单状态；`UNKNOWN` 继续只表示 Provider 事实不可信或不可得。
- 不绕过响应验签，不在验签失败后读取并信任 `code`、`msg`、订单号或状态。
- 不开放 `CLOSED -> PAID`；已验签迟到支付继续走现有 `REFUND_REQUIRED` 裁决。
- 不记录完整响应体、签名、规范串、密钥、完整订单号、`msg` 原文、Cookie、Token 或 `pay_info`。
- 遵守项目两阶段规则：第一阶段只编写代码和测试代码，不运行测试、编译、构建、真实 Provider 请求或部署；本计划末尾列出的命令只在用户明确批准第二阶段验证后执行。
- 当前工作区包含用户已有未提交改动；实现时必须在现有内容上做最小增量，禁止覆盖或回退这些改动。

### 0.2 当前已确认的失败位置

`LiuhaoPaymentRestClientImpl.postVerified()` 当前在读取 `timestamp` 和 `code` 前执行：

```java
if (!"RSA".equals(scalar(body.get("sign_type"))) || !signatures.verify(body)) {
    throw failure(
            MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
            "Liuhao response signature is invalid.");
}
```

因此生产日志只能证明失败发生在“`sign_type` 检查或 `verify()` 内部”，不能区分：

```text
SIGN_TYPE_MISSING
SIGN_TYPE_UNEXPECTED
SIGN_MISSING
SIGN_BASE64_INVALID
CANONICAL_FIELDS_UNEXPECTED
PLATFORM_SIGNATURE_MISMATCH
CRYPTO_VERIFIER_UNAVAILABLE
```

### 0.3 当前状态机缺口

当前 `MembershipClosingCheckConsumerServiceImpl` 在最终边界仍先判断 `close.status()`：

```text
closePayment -> UNKNOWN
-> !safeClosedStatus(close.status())
-> retryTerminal(message, order, envelope)
-> return
-> queryPayment 根本没有执行
```

三次终态重试耗尽后抛出 `MembershipPaymentTerminalQueryExhaustedException`，Redis 快照仍为 `CLOSING`。现有 `finalize_closing.lua` 又只允许安全 Provider 状态进入 `CLOSED`，所以只改 Java 消费者不足以完成目标。

### 0.4 目标时间线

```text
PENDING_PAYMENT
  -> 仍有下一检查点：发布下一阶段
  -> 最后一阶段但未到 expiresAt：精确调度到 expiresAt
  -> 到 expiresAt：最终查询
       -> PAID：进入现有支付事实链
       -> PENDING / UNKNOWN / 查询失败：原子进入 CLOSING

CLOSING（now < closingDeadlineAt）
  -> callback marker 存在：停止，由回调 Worker 收敛
  -> 幂等尝试 closePayment
  -> 有下一检查点：发布下一阶段
  -> 已到分段尾部但未到真实 deadline：精确调度到 closingDeadlineAt

CLOSING（now >= closingDeadlineAt）
  -> 幂等尝试 closePayment，结果只作为诊断事实
  -> 无条件独立调用 queryPayment
       -> PAID：进入支付事实链，禁止关闭
       -> CLOSED / EXPIRED / FAILED / REFUNDED：PROVIDER_CONFIRMED -> CLOSED
       -> PENDING / UNKNOWN / NOT_FOUND / 查询异常：TIMEOUT_UNCONFIRMED -> CLOSED
  -> Lua/CAS 再次检查状态、deadline 和 callback marker
  -> 成功终态后不再发布 closing 消息
```

## 1. 文件职责图

### 1.1 新增文件

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoSignatureVerificationReason.java`
  - 定义固定、低基数的验签结果原因，不携带响应原值。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoSignatureVerificationResult.java`
  - 保存验签是否成功和固定原因，并保证成功结果只能使用 `VERIFIED`。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/MembershipClosingFinalizationSource.java`
  - 区分 Provider 已确认关闭和本地截止时间未确认关闭。

### 1.2 修改文件

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoPaymentSignatureService.java`
  - 新增详细验签方法，保留兼容布尔入口。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentSignatureServiceImpl.java`
  - 在不输出敏感值的前提下区分签名失败层。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
  - 采集 HTTP、JSON、签名、时间戳和业务码各层结果，并在最终边界保持“先验签后读取业务字段”。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java`
  - 新增六号响应验签事件和 closing 最终裁决事件。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java`
  - 拆分截止前与最终边界控制流；最终查询不再依赖关单结果可信。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/MembershipOrderSnapshotStore.java`
  - 为外部 Provider 的 `finalizeClosing` 增加终态来源参数。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/impl/RedisMembershipOrderSnapshotStore.java`
  - 将终态来源作为 Lua 参数传入。
- `ai-temperate-service/src/main/resources/lua/membership-payment/finalize_closing.lua`
  - 对 `PROVIDER_CONFIRMED` 与 `TIMEOUT_UNCONFIRMED` 分别执行白名单校验，同时保留 deadline、marker 和状态 CAS。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentSignatureServiceImplTest.java`
  - 覆盖详细验签原因。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`
  - 覆盖响应分层日志和敏感信息不泄漏。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`
  - 替换“UNKNOWN 永久 CLOSING”的旧预期，覆盖最终查询与状态裁决矩阵。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisIntegrationTest.java`
  - 覆盖两类终态来源在 Lua/CAS 中的行为。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisArchitectureTest.java`
  - 锁定 Lua 仍检查 deadline、callback marker 和来源白名单。

---

### Task 1: 建立详细验签结果合同

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoSignatureVerificationReason.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoSignatureVerificationResult.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/LiuhaoPaymentSignatureService.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentSignatureServiceImpl.java:77-95`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentSignatureServiceImplTest.java`

- [ ] **Step 1: 先编写详细失败原因测试代码，但不执行**

在现有测试类中使用临时测试密钥构造以下用例：

```java
@Test
void reportsMissingSignTypeBeforeCryptographicVerification() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("code", 0);
    response.put("timestamp", "1788152400");
    response.put("sign", "AA==");

    assertThat(signatures.verifyDetailed(response).reason())
            .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_TYPE_MISSING);
}

@Test
void reportsUnexpectedSignTypeWithoutLoggingRawValue() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("code", 0);
    response.put("timestamp", "1788152400");
    response.put("sign_type", "unexpected-sensitive-value");
    response.put("sign", "AA==");

    assertThat(signatures.verifyDetailed(response).reason())
            .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_TYPE_UNEXPECTED);
}

@Test
void reportsMissingAndMalformedSignaturesSeparately() {
    Map<String, Object> missing = new LinkedHashMap<>();
    missing.put("code", 0);
    missing.put("timestamp", "1788152400");
    missing.put("sign_type", "RSA");

    Map<String, Object> malformed = new LinkedHashMap<>(missing);
    malformed.put("sign", "not-base64%%%");

    assertThat(signatures.verifyDetailed(missing).reason())
            .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_MISSING);
    assertThat(signatures.verifyDetailed(malformed).reason())
            .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_BASE64_INVALID);
}

@Test
void reportsPlatformSignatureMismatchAfterCanonicalization() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("code", 0);
    response.put("msg", "success");
    response.put("timestamp", "1788152400");
    response.put("sign_type", "RSA");
    response.put("sign", Base64.getEncoder().encodeToString("wrong".getBytes(UTF_8)));

    assertThat(signatures.verifyDetailed(response).reason())
            .isEqualTo(LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH);
}
```

- [ ] **Step 2: 新增固定原因枚举**

新增完整枚举，保持中文 JavaDoc，禁止携带动态文本：

```java
public enum LiuhaoSignatureVerificationReason {
    VERIFIED,
    SIGN_TYPE_MISSING,
    SIGN_TYPE_UNEXPECTED,
    SIGN_MISSING,
    SIGN_BASE64_INVALID,
    CANONICAL_FIELDS_UNEXPECTED,
    PLATFORM_SIGNATURE_MISMATCH,
    CRYPTO_VERIFIER_UNAVAILABLE
}
```

- [ ] **Step 3: 新增不可变验签结果**

```java
public record LiuhaoSignatureVerificationResult(
        LiuhaoSignatureVerificationReason reason) {

    public LiuhaoSignatureVerificationResult {
        Objects.requireNonNull(reason);
    }

    public boolean verified() {
        return reason == LiuhaoSignatureVerificationReason.VERIFIED;
    }

    public static LiuhaoSignatureVerificationResult success() {
        return new LiuhaoSignatureVerificationResult(
                LiuhaoSignatureVerificationReason.VERIFIED);
    }

    public static LiuhaoSignatureVerificationResult failed(
            LiuhaoSignatureVerificationReason reason) {
        if (reason == LiuhaoSignatureVerificationReason.VERIFIED) {
            throw new IllegalArgumentException("A failed verification cannot be VERIFIED.");
        }
        return new LiuhaoSignatureVerificationResult(reason);
    }
}
```

- [ ] **Step 4: 扩展签名服务接口并保留兼容入口**

```java
LiuhaoSignatureVerificationResult verifyDetailed(Map<String, ?> parameters);

default boolean verify(Map<String, ?> parameters) {
    return verifyDetailed(parameters).verified();
}
```

删除实现类对旧 `boolean verify(parameters)` 的直接覆盖，改为实现 `verifyDetailed(parameters)`。

- [ ] **Step 5: 按严格顺序实现分层验签**

实现顺序固定为：

```text
sign_type 是否存在
-> sign_type 是否严格等于 RSA
-> sign 是否存在且非空
-> sign 是否为标准 Base64
-> 响应字段是否能构造规范串
-> SHA256WithRSA 是否可初始化
-> 平台公钥验签结果
```

核心实现必须等价于：

```java
@Override
public LiuhaoSignatureVerificationResult verifyDetailed(Map<String, ?> parameters) {
    Objects.requireNonNull(parameters);
    Object rawSignType = parameters.get("sign_type");
    if (rawSignType == null || (rawSignType instanceof String text && text.isBlank())) {
        return failed(SIGN_TYPE_MISSING);
    }
    if (!(rawSignType instanceof String signType) || !"RSA".equals(signType)) {
        return failed(SIGN_TYPE_UNEXPECTED);
    }

    Object rawSignature = parameters.get("sign");
    if (!(rawSignature instanceof String encoded) || encoded.isBlank()) {
        return failed(SIGN_MISSING);
    }

    byte[] signature;
    try {
        signature = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
        return failed(SIGN_BASE64_INVALID);
    }

    byte[] canonical;
    try {
        if (containsNonScalarValue(parameters)) {
            return failed(CANONICAL_FIELDS_UNEXPECTED);
        }
        Map<String, String> values = scalarValues(parameters);
        values.remove("sign");
        values.remove("sign_type");
        canonical = canonicalize(values).getBytes(StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
        return failed(CANONICAL_FIELDS_UNEXPECTED);
    }

    try {
        Signature verifier = Signature.getInstance("SHA256WithRSA");
        verifier.initVerify(platformPublicKey);
        verifier.update(canonical);
        return verifier.verify(signature)
                ? LiuhaoSignatureVerificationResult.success()
                : failed(PLATFORM_SIGNATURE_MISMATCH);
    } catch (java.security.GeneralSecurityException exception) {
        return failed(CRYPTO_VERIFIER_UNAVAILABLE);
    }
}
```

`containsNonScalarValue(parameters)` 只用于响应验签，遇到数组、集合、Map、二进制或 `Resource` 返回 `true`。请求签名的现有排除规则保持不变；响应验签不能静默忽略复合字段。

- [ ] **Step 6: 静态自查签名安全边界**

确认新增类型不包含以下字段或 Getter：

```text
rawSignature
canonicalString
privateKey
publicKey
responseBody
msg
orderId
```

第一阶段不运行测试；第二阶段验证命令见 Task 7。

---

### Task 2: 在六号 HTTP 边界增加单条分层诊断日志

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java:363-478`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java:94-177`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 先增加日志契约测试代码，但不执行**

至少新增以下四个测试：

```java
@Test
void closeResponseWithoutSignatureReportsSignatureMetadataLayer(CapturedOutput output) {
    Map<String, Object> response = commonResponse(0);
    response.put("msg", "provider-sensitive-message-7c39");
    response.remove("sign");
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
            .isInstanceOf(MembershipPaymentException.class);

    assertThat(output.getAll())
            .contains("event=liuhao_response_verification")
            .contains("operation=close")
            .contains("verification_stage=signature_metadata")
            .contains("reason=SIGN_MISSING")
            .contains("provider_code=untrusted")
            .doesNotContain(ORDER_ID)
            .doesNotContain("provider-sensitive-message-7c39");
}

@Test
void closeResponseWithMalformedBase64ReportsEncodingLayer(CapturedOutput output) {
    Map<String, Object> response = commonResponse(0);
    response.put("sign", "not-base64%%%");
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
            .isInstanceOf(MembershipPaymentException.class);

    assertThat(output.getAll())
            .contains("verification_stage=signature_encoding")
            .contains("reason=SIGN_BASE64_INVALID");
}

@Test
void closeResponseWithWrongRsaSignatureReportsCryptoLayer(CapturedOutput output) {
    Map<String, Object> response = commonResponse(0);
    response.put("sign", Base64.getEncoder().encodeToString("wrong".getBytes(UTF_8)));
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
            .isInstanceOf(MembershipPaymentException.class);

    assertThat(output.getAll())
            .contains("verification_stage=rsa_verification")
            .contains("reason=PLATFORM_SIGNATURE_MISMATCH");
}

@Test
void successfulCloseVerificationLogsOnlyNormalizedMetadata(CapturedOutput output)
        throws Exception {
    Map<String, Object> close = closeResponse(0, ORDER_ID, TRADE_NO);
    close.put("msg", "provider-sensitive-message-8d42");
    server.expect(requestTo(BASE_URL + "/api/pay/close"))
            .andRespond(withSuccess(
                    json(signed(close)),
                    MediaType.APPLICATION_JSON));
    server.expect(requestTo(BASE_URL + "/api/pay/query"))
            .andRespond(withSuccess(
                    json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                    MediaType.APPLICATION_JSON));

    client.closePayment(new PaymentCloseCommand(ORDER_ID, null));

    assertThat(output.getAll())
            .contains("verification_stage=complete")
            .contains("verification_outcome=verified")
            .contains("provider_code=0")
            .doesNotContain(ORDER_ID)
            .doesNotContain(TRADE_NO)
            .doesNotContain("provider-sensitive-message-8d42");
}
```

- [ ] **Step 2: 保留 HTTP 元数据而不是只返回 `byte[]`**

在 `LiuhaoPaymentRestClientImpl` 中新增私有响应载体：

```java
private record LiuhaoHttpResponse(
        int status,
        String contentTypeClass,
        String bodySizeBucket,
        byte[] body) {
}
```

`RestClient.exchange(handler)` 返回该载体。只把 Content-Type 归一化为：

```text
application_json
missing
unexpected
```

Body 大小只归一化为：

```text
zero
le_1k
le_4k
le_limit
over_limit
```

本任务只诊断 Content-Type，不额外增加新的拒绝规则，避免把日志改造变成协议行为变更。

- [ ] **Step 3: 增加固定字段轮廓，不记录任意字段名**

从解析后的 Map 只派生以下布尔值和类型分类：

```text
has_code
has_msg
has_timestamp
has_sign
has_sign_type
has_pid
has_trade_no
has_out_trade_no
has_status
has_trade_status
unexpected_field_present
code_json_type=number|string|missing|unexpected
msg_character_class=ascii|non_ascii|missing|unexpected
msg_whitespace_profile=none|leading|trailing|both|missing|unexpected
sign_type_class=rsa|missing|unexpected
```

禁止记录未知字段名称集合；只记录 `unexpected_field_present=true|false`，防止不可信输入污染日志标签。该布尔值按 operation 使用固定白名单计算：关单只允许 `code/msg/timestamp/sign/sign_type/pid/trade_no/out_trade_no/status/trade_status`；查询、下单和退款继续使用各自已有响应字段白名单。

- [ ] **Step 4: 新增统一诊断方法**

在 `MembershipPaymentLifecycleDiagnostics` 新增 `liuhaoResponseVerification`，事件名固定为：

```text
event=liuhao_response_verification
```

实际方法签名固定为：

```java
public static void liuhaoResponseVerification(
        String operation,
        String httpOutcome,
        String httpStatusClass,
        String contentType,
        String bodySizeBucket,
        String jsonShape,
        boolean hasCode,
        boolean hasMsg,
        boolean hasTimestamp,
        boolean hasSign,
        boolean hasSignType,
        boolean hasPid,
        boolean unexpectedFieldPresent,
        String codeJsonType,
        String msgCharacterClass,
        String msgWhitespaceProfile,
        String signTypeClass,
        String verificationStage,
        String verificationOutcome,
        String reason,
        String providerCode,
        String traceId)
```

方法参数必须全部是固定枚举、归一化字符串、布尔值或数值桶。日志至少包含：

```text
operation
http_outcome
http_status_class
content_type
body_size_bucket
json_shape
has_code
has_msg
has_timestamp
has_sign
has_sign_type
has_pid
unexpected_field_present
code_json_type
msg_character_class
msg_whitespace_profile
sign_type_class
verification_stage
verification_outcome
reason
provider_code
traceId
```

失败时 `provider_code=untrusted`；只有 RSA 和 timestamp 均通过后才允许记录归一化的业务 `code`。

- [ ] **Step 5: 在 `postVerified()` 中按层设置最终诊断结果**

严格按以下阶段推进：

```text
transport
response_size
json_shape
signature_metadata
signature_encoding
canonicalization
rsa_verification
timestamp_validation
business_code
complete
```

每次调用最多输出一条 `liuhao_response_verification` 最终事件；`liuhaoCloseClient` 可以继续输出生命周期事件，但不得重复打印响应字段轮廓。

验签调用改为：

```java
LiuhaoSignatureVerificationResult verification = signatures.verifyDetailed(body);
if (!verification.verified()) {
    MembershipPaymentLifecycleDiagnostics.liuhaoResponseVerification(
            operationName(path),
            "success",
            httpStatusClass(httpResponse.status()),
            httpResponse.contentTypeClass(),
            httpResponse.bodySizeBucket(),
            "scalar_object",
            body.containsKey("code"),
            body.containsKey("msg"),
            body.containsKey("timestamp"),
            body.containsKey("sign"),
            body.containsKey("sign_type"),
            body.containsKey("pid"),
            hasUnexpectedResponseField(path, body),
            jsonType(body.get("code")),
            messageCharacterClass(body.get("msg")),
            messageWhitespaceProfile(body.get("msg")),
            signTypeClass(body.get("sign_type")),
            verificationStage(verification.reason()),
            "failed",
            verification.reason().name(),
            "untrusted",
            MembershipPaymentTraceContext.currentTraceId());
    throw failure(
            MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
            "Liuhao response signature is invalid.");
}
```

上述 `httpStatusClass`、`hasUnexpectedResponseField`、`jsonType`、`messageCharacterClass`、`messageWhitespaceProfile`、`signTypeClass` 和 `verificationStage` 都必须是本类私有纯函数，只返回本任务列出的固定值；禁止返回原始响应内容。

- [ ] **Step 6: 修正当前误导性异常分类**

不能继续把所有 `LIUHAO_RESPONSE_INVALID` 都记录成 `CLOSE_BUSINESS_CODE_REJECTED`。至少区分：

```text
CLOSE_RESPONSE_INVALID
CLOSE_BUSINESS_CODE_REJECTED
CLOSE_SIGNATURE_INVALID
CLOSE_REQUEST_FAILED
```

只有“已验签且 `code != 0`”才能使用 `CLOSE_BUSINESS_CODE_REJECTED`。JSON、字段形状或 timestamp 错误使用 `CLOSE_RESPONSE_INVALID`。

- [ ] **Step 7: 静态检查日志不泄密**

检查新增日志参数及测试断言，确认输出不含：

```text
sign
canonical string
raw response
msg raw value
ORDER_ID
TRADE_NO
private/public key material
```

---

### Task 3: 根据一次真实日志确定验签根因分支

**Files:**
- Inspect after deployment: application structured logs for `event=liuhao_response_verification operation=close`
- Reference: `docs/handoffs/2026-08-30-liuhao-closing-final-convergence-handoff.md`

- [ ] **Step 1: 第一阶段只交付诊断代码，不调用真实六号接口**

代码交付时明确记录：

```text
尚未证明六号关单验签失败的最终协议根因；
新增日志只用于把下一次失败定位到固定层。
```

- [ ] **Step 2: 用户批准部署和真实验证后只采集一条完整诊断链**

使用 `traceId` 关联以下事件，不搜索或输出完整订单号：

```text
membership_payment_close_lifecycle
liuhao_response_verification
membership_payment_closing_finalization
```

- [ ] **Step 3: 按固定决策表选择后续最小修复**

| 日志原因 | 已证明事实 | 后续修复方向 |
| --- | --- | --- |
| `SIGN_TYPE_MISSING` | 响应没有 `sign_type` | 确认六号成功/失败响应是否保证签名；不得跳过验签 |
| `SIGN_TYPE_UNEXPECTED` | 返回值不是严格 `RSA` | 对照六号原始响应和官方签名类型白名单；不得自行大小写兼容 |
| `SIGN_MISSING` | 响应没有可用 `sign` | 判断是否为未签名错误响应；继续检查请求是否被六号拒绝 |
| `SIGN_BASE64_INVALID` | `sign` 不是标准 Base64 | 确认是否使用 Base64URL、换行或 URL 编码；用官方样本离线验证 |
| `CANONICAL_FIELDS_UNEXPECTED` | 响应字段不能进入当前标量规范化 | 对照字段类型和签名排除规则，拒绝复合值歧义 |
| `PLATFORM_SIGNATURE_MISMATCH` | 元数据和 Base64 正常，但 RSA 不匹配 | 用六号提供的脱敏原始字段样本逐项比较字段集合、空值、`code` 类型、`msg` Unicode/空格和 `pid` |
| `CRYPTO_VERIFIER_UNAVAILABLE` | 本地密码学运行时失败 | 修复本地运行时或密钥装配，不修改六号协议算法 |

- [ ] **Step 4: 只为被日志证实的单一原因创建后续修复任务**

不得同时修改 RSA 算法、字段排序、Base64 和空值规则。一次只改一个被样本证明的变量，并先加入离线回归样本测试。

---

### Task 4: 为 CLOSING 原子终态增加明确来源

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/MembershipClosingFinalizationSource.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/MembershipOrderSnapshotStore.java:61-68`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/impl/RedisMembershipOrderSnapshotStore.java:689-725`
- Modify: `ai-temperate-service/src/main/resources/lua/membership-payment/finalize_closing.lua`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisIntegrationTest.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisArchitectureTest.java`

- [ ] **Step 1: 先编写外部 Provider 终态来源测试代码，但不执行**

新增集成测试用例，分别证明：

```java
@Test
void timeoutUnconfirmedAllowsUnknownOnlyAfterClosingDeadline() {
    String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
    orderStore.put(order(orderId));
    orderStore.startClosing(orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));

    MembershipOrderTransitionResult early = orderStore.finalizeClosing(
            orderId,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
            NOW.plusMinutes(9));
    assertThat(early.outcome()).isEqualTo(MembershipOrderTransitionOutcome.TOO_EARLY);

    MembershipOrderTransitionResult expired = orderStore.finalizeClosing(
            orderId,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
            NOW.plusMinutes(10));
    assertThat(expired.outcome()).isEqualTo(MembershipOrderTransitionOutcome.APPLIED);
    assertThat(expired.status()).isEqualTo(MembershipOrderStatus.CLOSED);
}

@Test
void providerConfirmedRejectsUnknownAtFinalBoundary() {
    String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
    orderStore.put(order(orderId));
    orderStore.startClosing(orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));

    MembershipOrderTransitionResult result = orderStore.finalizeClosing(
            orderId,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
            NOW.plusMinutes(11));
    assertThat(result.outcome())
            .isEqualTo(MembershipOrderTransitionOutcome.PROVIDER_STATUS_UNSAFE);
}

@Test
void timeoutUnconfirmedStillYieldsToCallbackMarker() {
    String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
    orderStore.put(order(orderId));
    orderStore.startClosing(orderId, NOW.plusMinutes(10), NOW.plusMinutes(5));
    redisTemplate.opsForValue().set(
            KEYS.membershipOrderCallbackMarkerKey(
                    new MembershipOrderRedisId(orderId)),
            "AaAjECcaAQGqi_h2Rl1Pig");

    MembershipOrderTransitionResult result = orderStore.finalizeClosing(
            orderId,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
            NOW.plusMinutes(11));
    assertThat(result.outcome())
            .isEqualTo(MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS);
}
```

- [ ] **Step 2: 新增终态来源枚举**

```java
public enum MembershipClosingFinalizationSource {
    PROVIDER_CONFIRMED,
    TIMEOUT_UNCONFIRMED
}
```

中文 JavaDoc 必须说明：该枚举表达本地终态的事实来源，不代表新的订单状态，也不证明跨 Redis 与 Provider 的强一致性。

- [ ] **Step 3: 扩展外部 Provider Store 契约**

将外部 Provider 重载改为：

```java
MembershipOrderTransitionResult finalizeClosing(
        String orderId,
        PaymentProviderStatus providerStatus,
        MembershipClosingFinalizationSource source,
        OffsetDateTime changedAt);
```

保留仅接收 `orderId, changedAt` 的本地模拟器重载，避免把本地模拟结果 Hash 的行为混入外部 Provider 来源参数。

- [ ] **Step 4: Redis 实现把来源作为第六个 Lua 参数传递**

```java
return transition(
        FINALIZE_CLOSING,
        keys,
        changedAtMicros,
        changedAtMillis,
        ttlMillis,
        orderId,
        providerStatus.name(),
        source.name());
```

- [ ] **Step 5: Lua 按来源执行不同白名单，但共享相同 CAS 前置条件**

保留现有顺序：

```text
快照存在
-> 当前状态是 CLOSING
-> closingDeadlineAt 已到
-> callback marker 不存在
-> 检查 finalization_source 和 provider_status
-> HSET CLOSED + version + dirty ZSET
```

来源裁决必须等价于：

```lua
local finalization_source = ARGV[6]

if not finalization_source or finalization_source == '' then
    -- 兼容只读取本地模拟 Provider Hash 的旧重载。
    finalization_source = 'PROVIDER_CONFIRMED'
end

local provider_confirmed_safe = provider_status == 'UNPAID'
        or provider_status == 'CLOSED'
        or provider_status == 'EXPIRED'
        or provider_status == 'FAILED'
        or provider_status == 'REFUNDED'

local timeout_unconfirmed_safe = provider_status == 'PENDING'
        or provider_status == 'UNKNOWN'

if finalization_source == 'PROVIDER_CONFIRMED' then
    if not provider_confirmed_safe then
        return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
    end
elseif finalization_source == 'TIMEOUT_UNCONFIRMED' then
    if not timeout_unconfirmed_safe then
        return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
    end
else
    return 'NOT_ALLOWED|CLOSING|' .. current_version
end
```

- [ ] **Step 6: 更新 Redis 架构测试合同**

架构测试必须检查 Lua 同时包含：

```text
status ~= 'CLOSING'
deadline > changed_at_micros
EXISTS marker_key
PROVIDER_CONFIRMED
TIMEOUT_UNCONFIRMED
PENDING
UNKNOWN
PROVIDER_STATUS_UNSAFE
```

同时检查 Lua 没有把 `UNKNOWN` 无条件改成 `CLOSED`；只有 `TIMEOUT_UNCONFIRMED` 且 deadline 已到才能允许。

---

### Task 5: 重构 CLOSING 最终边界，UNKNOWN 复用正常时间链

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:98-227`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:299-344`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:456-543`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`

- [ ] **Step 1: 删除旧的永久 CLOSING 测试预期，先写目标测试代码但不执行**

删除或改写：

```text
unknownFinalStatusRetriesThreeTimesThenThrowsForDlqWithoutClosing
```

替换为以下核心测试：

```java
@Test
void unknownCloseAtFinalBoundaryStillQueriesAndTimeoutCloses() {
    MembershipOrderSnapshot closing = order(MembershipOrderStatus.CLOSING);
    when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(closing));
    when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
    when(provider.closePayment(any())).thenReturn(
            new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));
    when(provider.queryPayment(any())).thenReturn(
            provider(PaymentProviderStatus.UNKNOWN));
    when(orderStore.finalizeClosing(
            ORDER_ID,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
            .thenReturn(new MembershipOrderTransitionResult(
                    MembershipOrderTransitionOutcome.APPLIED,
                    MembershipOrderStatus.CLOSED,
                    3L));

    closingService().process(closingEnvelope(4, 0));

    InOrder ordered = inOrder(provider, orderStore);
    ordered.verify(provider).closePayment(any());
    ordered.verify(provider).queryPayment(any());
    ordered.verify(orderStore).finalizeClosing(
            ORDER_ID,
            PaymentProviderStatus.UNKNOWN,
            MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    verifyNoInteractions(closingPublisher);
}

@Test
void closeSignatureFailureCannotSkipFinalQuery() {
    when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
            MembershipOrderStatus.CLOSING)));
    when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
    when(provider.closePayment(any())).thenThrow(new MembershipPaymentException(
            MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
            "sensitive response"));
    when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.CLOSED));
    when(orderStore.finalizeClosing(
            eq(ORDER_ID),
            eq(PaymentProviderStatus.CLOSED),
            eq(MembershipClosingFinalizationSource.PROVIDER_CONFIRMED),
            any(OffsetDateTime.class)))
            .thenReturn(new MembershipOrderTransitionResult(
                    MembershipOrderTransitionOutcome.APPLIED,
                    MembershipOrderStatus.CLOSED,
                    3L));

    closingService().process(closingEnvelope(4, 0));

    verify(provider).queryPayment(any());
    verify(orderStore).finalizeClosing(
            eq(ORDER_ID),
            eq(PaymentProviderStatus.CLOSED),
            eq(MembershipClosingFinalizationSource.PROVIDER_CONFIRMED),
            any(OffsetDateTime.class));
}
```

- [ ] **Step 2: 为关单尝试和最终查询分别保留“可信状态”和“失败原因”两个维度**

在消费者内新增两个私有结果记录：

```java
private record CloseAttempt(
        PaymentCloseResult result,
        boolean trusted,
        String requestOutcome,
        String httpOutcome,
        String signatureOutcome,
        String reason) {
}

private record FinalQueryAttempt(
        PaymentQueryResult result,
        boolean trusted,
        String requestOutcome,
        String httpOutcome,
        String signatureOutcome,
        String reason) {
}
```

将 `closeOrUnknown` 返回类型从 `PaymentCloseResult` 改为 `CloseAttempt`：正常返回的 Provider 结果使用 `trusted=true`；捕获 HTTP、验签、解析或插件异常时使用 `trusted=false`，并把内部支付结果归一化为 `PaymentProviderStatus.UNKNOWN`。这样“已验签但业务状态为 UNKNOWN”和“因为验签失败而 UNKNOWN”不会再被日志混成同一种事实。

要求：

- 已验签返回 `PENDING/UNKNOWN`：`trusted=true`，分别使用 `FINAL_QUERY_PENDING` / `FINAL_QUERY_UNKNOWN`。
- HTTP、验签、解析或 Provider 插件异常：`trusted=false`，结果归一化为 `UNKNOWN`，原因使用固定枚举字符串。
- 不把“查询没有调用”记录为 `failed`；只有真实进入 `provider.queryPayment()` 后才允许 `requestOutcome=sent`。

- [ ] **Step 3: 先按真实时间边界分支，再解释关单结果**

`process()` 的顶层控制流调整为：

```java
CloseAttempt close = closeOrUnknown(
        order, envelope.traceId(), envelope.messageId());

if (boundaryCheckAt.isBefore(order.closingDeadlineAt())) {
    continueClosingBeforeDeadline(message, delays, order, close, envelope);
    return;
}

FinalQueryAttempt finalQuery = queryFinal(
        order, envelope.traceId(), envelope.messageId());
finalizeAtClosingDeadline(message, order, close, finalQuery, envelope);
```

新增三个私有方法，签名固定为：

```java
private void continueClosingBeforeDeadline(
        MembershipClosingCheckMessage message,
        List<Long> delays,
        MembershipOrderSnapshot order,
        CloseAttempt close,
        MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope)

private FinalQueryAttempt queryFinal(
        MembershipOrderSnapshot order,
        String traceId,
        String messageId)

private void finalizeAtClosingDeadline(
        MembershipClosingCheckMessage message,
        MembershipOrderSnapshot order,
        CloseAttempt close,
        FinalQueryAttempt finalQuery,
        MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope)
```

三个方法分别只负责截止前调度、最终查询观测和最终 CAS 裁决；不得把最终查询重新放回 `safeClosedStatus(close.result().status())` 分支内。

最终边界之前允许根据 `close.result().status()` 调度；最终边界之后禁止因为以下任何结果提前 return：

```text
close UNKNOWN
close PENDING
close HTTP 失败
close 验签失败
close JSON 失败
close 插件不支持
```

- [ ] **Step 4: 保持截止前 UNKNOWN 与正常 CLOSING 使用同一时间链**

截止前规则：

```text
close PAID
-> query + reconcilePaid
-> 仍走下一 closing 检查点，等待 callback marker

close CLOSED/EXPIRED/FAILED/REFUNDED
-> 不提前写本地 CLOSED
-> 精确调度到 closingDeadlineAt，保留回调窗口

close PENDING/UNKNOWN
-> 有下一 closing stage：发布下一 stage
-> 已到 stage 尾部：精确调度到 closingDeadlineAt
```

这一步就是“让 UNKNOWN 像正常 CLOSING 一样有下一个过期时间并走完整条链”，但不会把 Provider 未确认伪装成已确认。

- [ ] **Step 5: 实现最终查询裁决矩阵**

核心分派必须等价于：

```java
PaymentProviderStatus status = finalQuery.result().status();
if (status == PaymentProviderStatus.PAID) {
    boolean accepted = reconciliationService.reconcilePaid(order, finalQuery.result());
    if (!accepted) {
        retryTerminal(message, order, envelope);
    }
    return;
}

MembershipClosingFinalizationSource source = safeClosedStatus(status)
        ? MembershipClosingFinalizationSource.PROVIDER_CONFIRMED
        : MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED;

PaymentProviderStatus observed = safeClosedStatus(status)
        ? status
        : PaymentProviderStatus.UNKNOWN.equals(status)
                ? PaymentProviderStatus.UNKNOWN
                : PaymentProviderStatus.PENDING;

MembershipOrderTransitionResult transition = orderStore.finalizeClosing(
        message.orderId(),
        observed,
        source,
        MembershipPaymentTime.now(clock));
```

查询异常统一以 `UNKNOWN + TIMEOUT_UNCONFIRMED` 进入 CAS；可信 `PENDING` 保持 `PENDING + TIMEOUT_UNCONFIRMED`，便于日志区分。

- [ ] **Step 6: 精确处理每一种 CAS 结果**

禁止继续使用“没有命中失败分支就记录 FINALIZED_CLOSED”的隐式逻辑。先增加以下私有日志包装方法，参数全部来自当前订单、消息、关单结果、最终查询和 CAS：

```java
private void logFinalization(
        MembershipOrderSnapshot order,
        MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope,
        CloseAttempt close,
        FinalQueryAttempt finalQuery,
        MembershipClosingFinalizationSource source,
        boolean callbackMarker,
        String transition,
        String nextAction,
        String reason) {
    MembershipPaymentLifecycleDiagnostics.closingFinalization(
            order,
            PaymentProviderReference.resolve(
                    order.providerTradeNo(), properties.defaultProvider()),
            callbackMarker,
            close.trusted(),
            finalQuery.requestOutcome(),
            finalQuery.httpOutcome(),
            finalQuery.signatureOutcome(),
            finalQuery.result().status(),
            source,
            transition,
            nextAction,
            reason,
            envelope.traceId(),
            envelope.messageId());
}
```

然后显式处理所有可接受结果，其余结果立即失败：

```java
switch (transition.outcome()) {
    case APPLIED -> {
        String reason = source == MembershipClosingFinalizationSource.PROVIDER_CONFIRMED
                ? "FINALIZED_CLOSED_PROVIDER_CONFIRMED"
                : "FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED";
        logFinalization(
                order, envelope, close, finalQuery, source,
                false,
                "closing_to_closed", "stop", reason);
        return;
    }
    case ALREADY_APPLIED -> {
        logFinalization(
                order, envelope, close, finalQuery, source,
                false,
                "already_closed", "stop", "FINALIZATION_IDEMPOTENT");
        return;
    }
    case CALLBACK_IN_PROGRESS -> {
        logFinalization(
                order, envelope, close, finalQuery, source,
                true,
                "none", "callback_worker", "FINALIZATION_CALLBACK_IN_PROGRESS");
        return;
    }
    case NOT_ALLOWED -> {
        logFinalization(
                order, envelope, close, finalQuery, source,
                false,
                "concurrent_terminal", "stop", "FINALIZATION_CONCURRENT_TERMINAL");
        return;
    }
    case TOO_EARLY, MISSING -> {
        retryTerminal(message, order, envelope);
        return;
    }
    case PROVIDER_STATUS_UNSAFE -> throw new IllegalStateException(
            "Closing finalization source and provider status are inconsistent.");
    default -> throw new IllegalStateException(
            "Unexpected closing transition outcome: " + transition.outcome());
}
```

`NOT_ALLOWED` 返回的当前状态如果是 `PAID/CLOSED/CANCELLED`，只记录幂等或并发终态，不得覆盖。

- [ ] **Step 7: 缩小终态重试的职责**

`retryTerminal()` 不再处理 Provider `PENDING/UNKNOWN`。它只允许用于：

```text
PAID 事实无法成功加入现有 callback ready 链
Redis/CAS 暂时不可用
快照暂时缺失
时间精度导致 TOO_EARLY
```

因此 `terminalQueryMaxRetries` 耗尽不再是 `UNKNOWN` 的正常业务终点。Provider `UNKNOWN` 到 deadline 后必须进入 `TIMEOUT_UNCONFIRMED -> CLOSED`。

---

### Task 6: 增加最终裁决日志并完成状态机回归测试代码

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`

- [ ] **Step 1: 新增最终裁决专用日志方法**

新增事件：

```text
event=membership_payment_closing_finalization
```

方法签名固定为：

```java
public static void closingFinalization(
        MembershipOrderSnapshot order,
        PaymentProviderType provider,
        boolean callbackMarker,
        boolean closeResultTrusted,
        String finalQueryRequest,
        String finalQueryHttp,
        String finalQuerySignature,
        PaymentProviderStatus finalQueryStatus,
        MembershipClosingFinalizationSource finalizationSource,
        String transition,
        String nextAction,
        String reason,
        String traceId,
        String messageId)
```

字段固定为：

```text
provider
trigger=final_boundary
local_status=closing
callback_marker
close_result_trusted
final_query_request=sent|skipped
final_query_http=success|failed|not_available
final_query_signature=verified|failed|not_available
final_query_status=paid|closed|expired|pending|unknown
finalization_source=provider_confirmed|timeout_unconfirmed|callback_worker|none
transition
next_action
reason
traceId
messageId
```

- [ ] **Step 2: 使用固定原因码区分相同 CLOSED 的不同事实来源**

```text
FINAL_QUERY_PAID
FINAL_QUERY_CONFIRMED_CLOSED
FINAL_QUERY_PENDING_TIMEOUT_CLOSED
FINAL_QUERY_UNKNOWN_TIMEOUT_CLOSED
FINAL_QUERY_FAILED_TIMEOUT_CLOSED
FINAL_CLOSE_FAILED_CONTINUING_QUERY
FINAL_CLOSE_SKIPPED_CALLBACK_IN_PROGRESS
FINALIZED_CLOSED_PROVIDER_CONFIRMED
FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED
FINALIZATION_CALLBACK_IN_PROGRESS
FINALIZATION_CONCURRENT_TERMINAL
```

Provider 已确认日志例子：

```text
event=membership_payment_closing_finalization provider=liuhao
trigger=final_boundary local_status=closing callback_marker=false
close_result_trusted=true final_query_request=sent final_query_http=success
final_query_signature=verified final_query_status=closed
finalization_source=provider_confirmed transition=closing_to_closed
next_action=stop reason=FINALIZED_CLOSED_PROVIDER_CONFIRMED
traceId=<token> messageId=<token>
```

本地超时未确认日志例子：

```text
event=membership_payment_closing_finalization provider=liuhao
trigger=final_boundary local_status=closing callback_marker=false
close_result_trusted=false final_query_request=sent final_query_http=failed
final_query_signature=not_available final_query_status=unknown
finalization_source=timeout_unconfirmed transition=closing_to_closed
next_action=stop reason=FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED
traceId=<token> messageId=<token>
```

- [ ] **Step 3: 补齐最终状态矩阵测试代码**

在消费者测试中覆盖：

```text
close UNKNOWN + query PAID -> reconcilePaid，绝不 finalizeClosing
close signature failure + query CLOSED -> PROVIDER_CONFIRMED -> CLOSED
close UNKNOWN + query PENDING -> TIMEOUT_UNCONFIRMED -> CLOSED
close UNKNOWN + query UNKNOWN -> TIMEOUT_UNCONFIRMED -> CLOSED
close UNKNOWN + query exception -> TIMEOUT_UNCONFIRMED -> CLOSED
close CLOSED + query PENDING -> TIMEOUT_UNCONFIRMED -> CLOSED
callback marker 在外部调用前存在 -> 不关单、不查询、不 CAS
callback marker 在 Lua CAS 前出现 -> CALLBACK_IN_PROGRESS，不发布重试
并发已经 PAID -> NOT_ALLOWED/PAID，禁止覆盖
重复最终消息 -> ALREADY_APPLIED/CLOSED，禁止重复发布或结算
deadline 前 UNKNOWN -> 继续原有 stage 或精确调度 deadline
```

- [ ] **Step 4: 补充日志泄密测试**

所有失败用例将异常消息设置为：

```text
sensitive provider response
```

然后断言完整输出：

```java
assertThat(output.getAll())
        .contains("reason=FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED")
        .doesNotContain("sensitive provider response")
        .doesNotContain(ORDER_ID)
        .doesNotContain(TRADE_NO);
```

- [ ] **Step 5: 保留迟到支付退款合同**

只读确认并在现有 callback 测试中增加或保留断言：

```text
本地 CLOSED 收到第一条已验签支付事实
-> MembershipPaymentCallbackResolution.REFUND_REQUIRED
-> 不执行 CLOSED -> PAID
-> 不静默丢弃支付事实
```

---

### Task 7: 两阶段验证、日志判读与交付

**Files:**
- Review: all files listed in Tasks 1-6
- Update if implementation changes behavior wording: `docs/handoffs/2026-08-30-liuhao-closing-final-convergence-handoff.md`

- [ ] **Step 1: 第一阶段完成静态审查，不运行命令**

逐项确认：

```text
所有新增/修改 Java 顶级类型有中文 JavaDoc
复杂验签、CAS 和并发顺序有紧邻中文注释
无新的数据库字段或迁移
无新的 RabbitMQ 拓扑
YAML 未修改；如意外修改，必须恢复或补齐逐行中文注释
没有完整响应、签名、规范串、密钥、订单号或 msg 原文日志
UNKNOWN 最终分支没有调用 retryTerminal
最终 queryPayment 不再由 close.status() 前置控制
Lua 仍先检查 deadline 和 callback marker
TIMEOUT_UNCONFIRMED 只允许 PENDING/UNKNOWN
PROVIDER_CONFIRMED 只允许安全终态
```

- [ ] **Step 2: 第一阶段交付时明确列出未执行验证**

必须明确声明未执行：

```text
单元测试
Redis 集成测试
Spring 上下文测试
编译
打包
真实六号请求
部署
日志复现
```

- [ ] **Step 3: 用户明确批准第二阶段后运行签名和 REST 客户端测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=LiuhaoPaymentSignatureServiceImplTest,LiuhaoPaymentRestClientImplTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

```text
BUILD SUCCESS
验签详细原因测试全部通过
日志泄密断言全部通过
```

- [ ] **Step 4: 用户明确批准后运行消费者状态机测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=MembershipPaymentCheckConsumerServiceImplTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

```text
BUILD SUCCESS
UNKNOWN 最终边界调用 queryPayment
PENDING/UNKNOWN/查询失败均使用 TIMEOUT_UNCONFIRMED 进入 CLOSED
PAID、callback marker 和并发终态不被覆盖
```

- [ ] **Step 5: 用户明确批准且 Redis 测试环境可用后运行 Redis 合同测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=MembershipPaymentRedisArchitectureTest,MembershipPaymentRedisIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

```text
BUILD SUCCESS
PROVIDER_CONFIRMED + UNKNOWN 返回 PROVIDER_STATUS_UNSAFE
TIMEOUT_UNCONFIRMED + UNKNOWN 在 deadline 前返回 TOO_EARLY
TIMEOUT_UNCONFIRMED + UNKNOWN 在 deadline 后进入 CLOSED
callback marker 始终返回 CALLBACK_IN_PROGRESS
```

- [ ] **Step 6: 用户明确批准后运行 Spring 上下文验证**

本次计划不修改 YAML，但 Store 接口和 Spring Bean 构造可能受 Java 签名变化影响，因此运行项目现有 membership payment 配置/上下文测试：

```powershell
mvn -pl ai-temperate-web -am `
  -Dtest=MembershipPaymentConfigurationContractTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

```text
BUILD SUCCESS
Membership payment 相关 Bean 正常装配
```

- [ ] **Step 7: 用户明确批准部署后完成一次受控日志验收**

验收一笔未支付六号订单：

```text
进入 PENDING_PAYMENT
-> 到 expiresAt 查询
-> 未支付或 UNKNOWN 进入 CLOSING
-> closing 阶段按原时间链执行
-> 到 closingDeadlineAt 无条件发出 final query
-> 最终本地状态不再永久 CLOSING
```

同时确认日志中出现且只出现规范化诊断：

```text
event=liuhao_response_verification operation=close
event=membership_payment_closing_finalization trigger=final_boundary
```

- [ ] **Step 8: 根据真实验签原因决定是否建立独立协议修复计划**

如果日志结果是 `PLATFORM_SIGNATURE_MISMATCH`，先向六号取得一组可离线使用的原始字段和签名样本，再单独编写最小协议兼容修复；不得在本计划中预先猜测并修改 RSA 算法。

---

## 验收标准

### Bug 1：验签诊断

- 一次关单失败最多输出一条 `liuhao_response_verification` 最终事件。
- 日志能够明确指出失败层及固定原因，而不是只有 `LIUHAO_SIGNATURE_INVALID`。
- 未验签响应的 `code` 永远标记为 `untrusted`。
- 日志不包含签名、规范串、完整响应、`msg` 原文、完整订单号或密钥。
- 下单和查询仍使用原有严格验签；不因为诊断改造降低安全要求。

### Bug 2：UNKNOWN 收敛

- `UNKNOWN` 在 deadline 前复用现有 closing stage 和最终精确调度。
- `closePayment()` 返回或抛出任何结果，都不能阻止最终 `queryPayment()`。
- 最终查询 `PAID` 永不关闭，并进入现有支付事实链。
- 最终查询 `CLOSED/EXPIRED/FAILED/REFUNDED` 使用 `PROVIDER_CONFIRMED` 进入 `CLOSED`。
- 最终查询 `PENDING/UNKNOWN` 或发生查询异常，使用 `TIMEOUT_UNCONFIRMED` 进入 `CLOSED`。
- callback marker、并发 `PAID`、重复最终消息均由 Lua/CAS 正确保护。
- `UNKNOWN` 不再以 `TERMINAL_RETRY_EXHAUSTED + keep_closing` 作为正常终点。
- 两种 `CLOSED` 在日志中明确区分，任何日志都不得把本地超时关闭表述为六号已确认关闭。

## 非目标

- 本计划不直接断言六号当前签名算法错误。
- 本计划不在没有原始样本证据时兼容 Base64URL、忽略 `sign_type`、调整大小写或跳过错误响应验签。
- 本计划不保证本地 `CLOSED` 与六号远端状态强一致。
- 本计划不解决跨 PostgreSQL、Redis、RabbitMQ 与六号的分布式事务问题。
- 本计划不新增长期审计字段；两类关闭来源先通过固定日志和指标区分。
