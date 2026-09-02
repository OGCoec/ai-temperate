# 六号易支付关单与平台流水回填修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复六号响应成功码误判、平台流水未回填和关单故障不可观测问题，使既有 `CLOSING` 状态机在不增加字段的前提下可靠收敛。

**Architecture:** 继续复用 `membership_order.provider_trade_no`：页面跳转创建后先保存 `LIUHAO:ORDER:<out_trade_no>`，可信查询、回调或关单响应取得平台流水后原位替换为 `LIUHAO:TRADE:<trade_no>`。不新增数据库字段、Redis Key、RabbitMQ 拓扑或状态；首次关单仍在进入 `CLOSING` 时执行，本地仍等待五分钟边界后才进入 `CLOSED`。

**Tech Stack:** Java 21、Spring RestClient、Spring Boot、MyBatis、PostgreSQL、Redis、RabbitMQ、JUnit 5、Mockito、AssertJ。

---

## 实施状态（2026-08-30）

- [x] 已写入六号 RestClient 成功码、验签、冲突和超时合同测试代码。
- [x] 已把六号接口调用成功条件从错误的 `code=1` 改为 `code=0`。
- [x] 已在主动查询和关单结果中复用现有字段绑定可信 `LIUHAO:TRADE:<trade_no>`。
- [x] 已补充 Redis 流水冲突保持非终态、关单不提前结束五分钟窗口的测试代码。
- [x] 已增加受控错误码日志和低基数 Provider 失败指标。
- [ ] 未运行 Maven、编译或测试；项目分阶段规则要求获得当前验证阶段的明确授权。
- [ ] 未执行真实六号重复关单联调、部署或旧卡单重投。

---

## 已确认根因

1. `out_trade_no` 是商户订单号，商户就是本项目，因此它等于本项目 22 字符业务订单号是正确行为，不是六号平台号。
2. `trade_no` 才是六号系统订单号。页面 Form POST 直接由浏览器提交，商户后端当次拿不到六号响应，所以初始值只能是 `LIUHAO:ORDER:<out_trade_no>`。
3. 修复前的 `LiuhaoPaymentRestClientImpl.postVerified()` 错误地把 `code=1` 当成功；六号 V2 接口成功码是 `code=0`。查询和关单的正确响应因此被抛成异常。
4. 关单消费者把该异常保守映射为 `UNKNOWN`；`UNKNOWN` 禁止 `CLOSING -> CLOSED`，所以订单停留在非终态。
5. 即使查询返回可信 `trade_no`，当前未支付路径也不会调用既有绑定逻辑；只有支付回调/已支付收敛路径会把 `ORDER` 替换为 `TRADE`。
6. 当前消费者日志只记录异常类名，不能直接看出是六号超时、验签失败、响应错误还是业务拒绝，增加了排障难度。

## 明确不做

- 不新增 `provider` 字段或任何数据库列。
- 不修改 `MembershipOrder`、`MembershipOrderSnapshot` 和回调表结构。
- 不改变状态枚举、五分钟 `CLOSING` 窗口或 `CLOSING -> PAID`。
- 不删除现有订单、RabbitMQ 消息或任务。
- 不手工把未知订单直接改成 `CLOSED`。
- 不在日志输出密钥、签名、完整请求或完整响应。

### Task 1: 用客户端合同测试复现成功码错误

**Files:**
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`
- Reference: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`

- [ ] **Step 1: 建立不访问公网的 RestClient 测试夹具**

使用 `MockRestServiceServer`、固定 `Clock` 和测试 RSA 密钥构造客户端；所有响应必须经过测试平台私钥签名，禁止绕过验签。

- [ ] **Step 2: 写入当前必然失败的查询测试**

```java
@Test
void acceptsCodeZeroAndReturnsPlatformTradeNumberForPendingOrder() {
    // 响应包含 code=0、status=0、out_trade_no、trade_no、timestamp、sign_type 和有效平台签名。
    PaymentQueryResult result = client.queryPayment(
            new PaymentQueryCommand(ORDER_ID, null));

    assertThat(result.status()).isEqualTo(PaymentProviderStatus.PENDING);
    assertThat(result.orderId()).isEqualTo(ORDER_ID);
    assertThat(result.providerTradeNo()).isEqualTo(LIUHAO_TRADE_NO);
}
```

- [ ] **Step 3: 写入当前必然失败的关单测试**

```java
@Test
void acceptsCodeZeroAsSuccessfulClose() {
    PaymentCloseResult result = client.closePayment(
            new PaymentCloseCommand(ORDER_ID, null));

    assertThat(result.status()).isEqualTo(PaymentProviderStatus.CLOSED);
}
```

- [ ] **Step 4: 固定反例合同**

增加 `code!=0`、错误签名、错误 `out_trade_no`、错误 `trade_no`、超时五类测试；这些结果均不得变成 `CLOSED`。

- [ ] **Step 5: 第二阶段获准后运行定向测试**

```text
mvn -pl ai-temperate-service -am -Dtest=LiuhaoPaymentRestClientImplTest test
```

预期：修复前 `code=0` 两个测试失败；安全反例继续通过。第一阶段只写测试，不自动执行。

### Task 2: 修正六号 V2 接口成功语义

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java:271-305`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java`

- [ ] **Step 1: 将接口调用成功条件改为 `code=0`**

```java
String responseCode = scalar(body.get("code"));
if (!"0".equals(responseCode)) {
    throw invalid("Liuhao rejected the payment operation.");
}
```

验签、时间戳和 `pid` 校验仍必须先完成；不能因为 `code=0` 跳过可信响应校验。

- [ ] **Step 2: 保持支付事实与接口成功分离**

`code=0` 只表示查询/关单接口调用成功；查询仍使用 `status`/`trade_status` 映射 `PENDING`、`PAID`、`REFUNDED`、`CLOSED`，不得把所有 `code=0` 查询都解释成已支付或已关闭。

- [ ] **Step 3: 保持保守失败策略**

关闭接口返回非零码、超时、验签失败或身份冲突时继续返回异常，由状态机映射为 `UNKNOWN`；禁止为了让订单终态化而强制返回 `CLOSED`。

### Task 3: 将可信六号系统流水原位写回现有字段

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipPaymentCheckConsumerServiceImpl.java:115-134`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:105-130`
- Reuse: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentAttemptTransactionService.java`
- Reuse: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/MembershipOrderSnapshotStore.java`
- Reuse: `ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/MembershipOrderMapper.xml:199-210`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java`

- [ ] **Step 1: 在最终主动查询后识别已解析流水**

仅当以下条件全部成立时才绑定：

```text
订单当前值是 LIUHAO:ORDER:<out_trade_no>
查询响应已验签并通过 pid、订单号校验
查询结果带 LIUHAO:TRADE:<trade_no>
新旧引用解析出的 Provider 都是 LIUHAO
```

- [ ] **Step 2: 复用现有数据库条件更新**

```java
transactionService.bindProviderTradeNo(
        order.loginIdentityId(),
        base64UrlCodec.decode(order.orderId()),
        query.providerTradeNo());
```

现有 Mapper 已只允许 `NULL`、`BAR:ORDER:%` 或 `LIUHAO:ORDER:%` 被替换，不修改 SQL 结构，不覆盖既有 `TRADE` 事实。

- [ ] **Step 3: 数据库提交后修补 Redis 快照**

```java
MembershipProviderTradeNoPatchOutcome outcome = orderStore.patchProviderTradeNo(
        order.orderId(),
        order.loginIdentityId(),
        query.providerTradeNo());
```

只接受 `APPLIED`、`UNCHANGED` 或可由数据库回源恢复的 `MISSING`；`CONFLICT` 必须中止本次终态迁移并记录受控错误，禁止覆盖并发回调已经写入的不同流水。

- [ ] **Step 4: 关单响应携带 `trade_no` 时执行同一绑定**

关单成功响应如果返回平台流水，也要在保持本地 `CLOSING` 的同时把引用升级为 `LIUHAO:TRADE:<trade_no>`；关单响应没有该字段时不伪造流水。

- [ ] **Step 5: 补齐消费者回归测试**

覆盖：

```text
PENDING 查询返回真实 trade_no -> DB 与 Redis 都从 ORDER 升级为 TRADE
关单返回真实 trade_no -> 本地仍是 CLOSING，但流水已升级
重复消息 -> 已有相同 TRADE 不重复产生副作用
不同 TRADE 冲突 -> 不进入 CLOSED
UNKNOWN/非法响应 -> 不写入任何平台流水
```

### Task 4: 验证五分钟后的重复关单合同

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipPaymentCheckConsumerServiceImplTest.java:242-505`
- Modify if required by confirmed provider contract: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java:165-178`

- [ ] **Step 1: 固定现有时序不变**

```text
PENDING_PAYMENT -> CLOSING
立即首次 closePayment
第三方安全关闭后本地仍保持 CLOSING 五分钟
最终边界再次幂等确认
确认安全后 CLOSING -> CLOSED
```

- [ ] **Step 2: 联调确认重复 `/api/pay/close` 的真实返回合同**

只记录脱敏后的 HTTP 状态、六号 `code` 和本项目错误码；不得记录 `sign`、密钥或完整响应。必须确认“已关闭订单再次关单”是继续 `code=0`，还是返回某个明确非零业务码。

- [ ] **Step 3: 按真实合同写测试，不猜测错误码**

若重复关单仍为 `code=0`，保持当前最终边界重复调用。若六号明确返回“已经关闭”的固定业务码，仅将该已确认码映射为幂等 `CLOSED`；未知码和“不支持关闭”仍为 `UNKNOWN`。

### Task 5: 提高排障可见性但不泄露敏感数据

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipPaymentCheckConsumerServiceImpl.java:137-156`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java:174-214`

- [ ] **Step 1: 消费者日志输出受控错误码**

当异常是 `MembershipPaymentException` 时，记录 `exception.code()`；其他异常只记录类名。日志保留 `traceId`、`messageId` 和操作名，不记录订单完整请求、签名或平台响应原文。

- [ ] **Step 2: 区分查询与关单失败指标**

复用现有低基数指标，至少区分：超时、不可达、验签失败、响应无效、订单冲突。标签不得包含 `trade_no`、`out_trade_no` 或完整 Redis Key。

- [ ] **Step 3: 增加日志契约测试**

确认错误码可见，平台 `msg`、`sign`、私钥、公钥和完整响应均不可见。

### Task 6: 修复后恢复现有卡住订单

**Files:**
- No schema or source changes; operational recovery only after Tasks 1-5 are deployed.

- [ ] **Step 1: 禁止删除该订单或直接改终态**

自然过期只能影响六号侧的可支付性，不能保证本项目从 `CLOSING` 收敛；直接删除任务或手工改 `CLOSED` 会丢失支付竞态和审计证据。

- [ ] **Step 2: 部署修复后重投现有消息**

若消息仍在重试链，允许新代码自然消费；若已经进入 DLQ，只重投该订单对应的关单消息。不得重投无边界的整队列。

- [ ] **Step 3: 用商户订单号主动查询并绑定平台流水**

对当前订单使用 `out_trade_no=AaBVT8qWAQGYyiS9xjCEcg` 查询。可信响应返回 `trade_no=2026083108542370629` 后，数据库应变成：

```text
LIUHAO:TRADE:2026083108542370629
```

- [ ] **Step 4: 验证最终状态**

首次/补偿关单成功后，本地在五分钟窗口内仍为 `CLOSING`；到达 `closing_deadline_at` 且无支付事实、无 callback marker、Provider 再次确认安全关闭后才进入 `CLOSED`。

## 推荐提交边界

```text
test: reproduce liuhao response contract failures
fix: accept liuhao v2 success responses
fix: bind resolved liuhao trade numbers
test: cover liuhao closing convergence
chore: improve liuhao payment diagnostics
```

## 第一版验收标准

- 六号 `code=0` 不再被误判为失败。
- `code=0,status=0` 只表示查询成功且未支付，不会误判成 `PAID` 或提前 `CLOSED`。
- 可信查询/关单拿到系统流水后，`LIUHAO:ORDER:<业务订单号>` 原位升级为 `LIUHAO:TRADE:<六号系统订单号>`。
- 订单进入 `CLOSING` 后立即关单，但本地仍等待原五分钟窗口。
- 未知结果、插件不支持关闭、验签失败或流水冲突均不会强制进入终态。
- 现有卡单通过定向重投恢复，不删除订单、不改表结构、不批量重放队列。
- 日志能够区分错误类型，但不泄露任何密钥、签名或完整支付报文。
