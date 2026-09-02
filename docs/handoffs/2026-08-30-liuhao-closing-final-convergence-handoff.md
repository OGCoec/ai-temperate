# 六号订单 `CLOSING` 最终收敛与关单验签排查交接

> 2026-08-31 第一阶段实现已按本文目标策略落入代码：最终查询不再依赖关单结果，关闭来源通过 `PROVIDER_CONFIRMED` 与 `TIMEOUT_UNCONFIRMED` 区分；测试、编译和外部联调尚未执行。

## 1. 交接目的

本文档用于把 2026-08-30 对六号易支付关单链路的生产日志排查结果、当前代码缺口和下一上下文需要实现的目标策略完整交接。

本次只写交接文档，没有修改后端代码、数据库、Redis、RabbitMQ、前端或 Cloudflare，也没有执行测试、编译、构建和部署。

## 2. 先给结论

当前可以确认的不是“六号一定没有执行关单”，而是：

1. 后端已经使用 `trade_no` 调用了六号 `/api/pay/close`。
2. 六号关单响应在本项目验签阶段失败，错误为 `LIUHAO_SIGNATURE_INVALID`。
3. 因为响应不可信，本项目无法读取并相信响应中的 `code` 和 `msg`，所以第三方是否实际完成关单处于 `UNKNOWN`。
4. 当前消费者在关单结果为 `UNKNOWN` 时提前进入重试分支，没有在最终边界执行独立订单反查。
5. 终态重试耗尽后只记录 `TERMINAL_RETRY_EXHAUSTED` 并继续保留 `CLOSING`，没有后续收敛任务，因此订单会永久停留在 `CLOSING`。

所以，当前问题包含两个彼此独立的部分：

- 六号关单响应为什么验签失败，需要单独排查协议兼容性。
- 即使关单响应为 `UNKNOWN`，本地状态机也必须在最终边界继续反查并结束 `CLOSING`，不能永久悬挂。

## 3. 已确认的日志证据

本次样本链路的 `traceId` 为：

```text
b9cf600d-46e5-4078-a74a-2999c0131ca1
```

只在本文记录链路 ID，不记录完整六号系统订单号、签名、响应体或跳转信息。

### 3.1 `ORDER → TRADE` 已正常工作

统一下单链路已经在接口返回前完成同步绑定：

```text
event=membership_payment_reference_bound
source=api_create
from_kind=order
to_kind=trade
database_bind=applied
redis_bind=applied
bind_latency_ms=214
```

这说明当前样本不是商户订单号与六号系统订单号混用。后续关单日志也明确记录：

```text
reference_kind=trade
locator_kind=trade_no
trade_no_present=true
```

即关单请求使用的是六号系统订单号定位。

### 3.2 进入 `CLOSING` 前订单仍为未支付

在进入关单流程前，主动查询成功并返回：

```text
provider_query_outcome=success
provider_status=pending
reference_kind=trade
```

这只能证明查询当时订单未支付，不能证明六号订单已经关闭。

### 3.3 关单响应持续验签失败

从首次关单到最终边界重试，日志反复出现：

```text
close_request=failed
signature_outcome=failed
provider_code=LIUHAO_SIGNATURE_INVALID
provider_status=unknown
reason=CLOSE_SIGNATURE_INVALID
```

这表示 HTTP 调用已经进入响应处理，但响应未通过平台公钥验签。它不等同于：

- 六号接口返回 404；
- 请求没有发出；
- 六号明确返回业务失败；
- 六号明确表示订单仍可支付；
- 六号明确表示订单已经关闭。

由于验签失败发生在读取业务结果之前，目前不能从日志判断六号返回的真实 `code`。

### 3.4 最终重试耗尽后永久保留 `CLOSING`

最终日志为：

```text
trigger=final_boundary
provider_status=unknown
followup_query=failed
next_action=keep_closing
reason=TERMINAL_RETRY_EXHAUSTED
```

随后没有新的长期补偿消息或调度任务，因此该订单不会自行进入 `CLOSED`。

## 4. 当前代码的实际缺口

主要代码位置：

```text
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/rabbit/impl/MembershipClosingCheckConsumerServiceImpl.java
```

当前控制流的核心问题是：

```text
调用 closePayment
→ close 返回 PAID：执行查询并收敛支付
→ close 不是安全关闭状态：重试或耗尽后保持 CLOSING，然后提前 return
→ 只有 close 已经返回安全关闭状态，最终边界才执行 queryPayment
```

因此，`closePayment()` 返回 `UNKNOWN` 时，`queryPayment()` 被关单结果挡住。最终边界日志里的：

```text
followup_query=failed
followup_query=unknown
```

不能证明真实查询请求已经发出；当前字段把“因为前置关单失败而跳过查询”与“查询实际发出后失败”混在了一起，具有误导性。

这属于状态机收敛缺口：

- `UNKNOWN → CLOSING` 本身合理，用于保留回调窗口。
- `CLOSING` 到期后仍以关单响应为前置条件，导致最终反查被跳过，不合理。
- 重试耗尽后没有新的调度来源，导致永久 `CLOSING`，是本次必须修复的核心问题。

## 5. 用户已明确选择的目标策略

用户已经明确选择“最终边界本地超时关闭”策略：

```text
关单响应 UNKNOWN
→ 允许进入并保持 CLOSING
→ 保留现有五分钟回调窗口
→ 到 closingDeadlineAt 再执行一次关单
→ 无论关单结果是否可信，都必须独立查询六号订单
→ 查询为 PAID：CLOSING → PAID
→ 查询为 CLOSED/EXPIRED：CLOSING → CLOSED
→ 查询仍为 PENDING/NOT_FOUND/UNKNOWN，或者最终查询仍无法确认：本地 CLOSING → CLOSED
```

关键约束是：

- 不再使用“有限次数重试耗尽后永久保持 `CLOSING`”作为终态。
- 最终反查不能依赖关单响应先成功。
- 最终边界必须产生本地终态，不能继续悬挂。

## 6. 必须区分两种 `CLOSED`

虽然数据库状态都使用现有 `CLOSED`，但业务和日志必须区分原因：

### 6.1 Provider 已确认关闭

```text
provider query = CLOSED/EXPIRED
→ CLOSING → CLOSED
→ reason=FINALIZED_CLOSED_PROVIDER_CONFIRMED
```

这是第三方事实确认后的关闭。

### 6.2 最终超时仍无法确认

```text
provider query = PENDING/NOT_FOUND/UNKNOWN，或查询本身失败
→ closing deadline 已到
→ CLOSING → CLOSED
→ reason=FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED
```

这是用户明确接受的本地超时收敛策略，不得在日志、指标或文档中表述为“六号已经确认关闭”。

本次默认不新增数据库字段，因此先通过结构化日志和指标区分两种原因。如果后续需要长期审计两种关闭来源，再单独申请字段或事实表设计，不得在本次顺手扩表。

## 7. 该策略主动接受的风险

必须明确：

```text
六号查询仍为 PENDING/UNKNOWN
≠ 六号订单已经关闭
```

最终强制本地 `CLOSED` 会接受以下风险：

1. 六号订单可能仍然可以被用户支付。
2. 本地已经 `CLOSED` 后，可能收到真实、已验签的迟到支付回调。
3. 如果现有状态机拒绝 `CLOSED → PAID`，迟到支付事实可能无法自动结算。
4. 如果查询本身因为网络或验签问题失败，本地关闭结论只是超时策略，不是第三方事实。

因此下一上下文实现前必须检查迟到支付处理：

- 在最终状态 CAS 前再次检查 callback marker，回调已开始时不得强制关闭。
- 已验签的迟到支付事实不得静默丢弃。
- 如果现有状态机不允许 `CLOSED → PAID`，至少必须进入现有支付事实对账或人工告警路径，并输出高优先级指标。
- 未经单独确认，不要擅自新增 `CLOSED → PAID` 状态迁移；先核对当前权益结算与退款边界。

## 8. 下一上下文的后端实施步骤

### 8.1 重构最终边界控制流

修改 `MembershipClosingCheckConsumerServiceImpl`，将“关单尝试”和“最终事实查询”拆成两个独立步骤：

```text
if now < closingDeadlineAt:
    调用 closePayment
    根据结果继续等待或发布现有重试消息
else:
    幂等调用 closePayment，结果只作为诊断事实
    无条件调用 queryPayment
    根据查询结果执行唯一一次终态裁决
```

禁止在最终边界因为以下结果提前 `return`：

- `CLOSE_SIGNATURE_INVALID`；
- HTTP 失败；
- 六号业务码未知；
- Provider 返回 `UNKNOWN`；
- Provider 插件不支持可靠关单确认。

### 8.2 最终查询裁决矩阵

| 最终查询结果 | 本地迁移 | 说明 |
| --- | --- | --- |
| `PAID` | `CLOSING → PAID` | 进入现有支付事实和权益结算链路 |
| `CLOSED` / `EXPIRED` / `FAILED` / `REFUNDED` | `CLOSING → CLOSED` | 第三方已确认终态 |
| `PENDING` | `CLOSING → CLOSED` | 到期后按本地超时策略关闭 |
| `NOT_FOUND` | `CLOSING → CLOSED` | 到期后按本地超时策略关闭 |
| `UNKNOWN` | `CLOSING → CLOSED` | 到期后按本地超时策略关闭 |
| 查询 HTTP、验签或解析失败 | `CLOSING → CLOSED` | 按用户选择的 fail-closed 策略收敛，并明确记录未确认风险 |

所有迁移继续使用现有 PostgreSQL 条件更新和 Redis Lua/CAS，禁止无条件覆盖并发回调结果。

### 8.3 保留回调优先级

终态迁移前必须重新读取 realtime guard：

- callback marker 存在：本次不强制关闭，让回调链完成。
- 已经 `PAID`：幂等结束，不得覆盖为 `CLOSED`。
- 已经 `CLOSED/CANCELLED`：幂等结束。
- 仍为同一笔 `CLOSING` 且 deadline 已到：才允许执行最终迁移。

### 8.4 修正诊断日志

不能再用 `followup_query=failed` 表示“根本没有调用查询”。建议至少区分：

```text
final_query_request=sent|skipped
final_query_http=success|failed|not_available
final_query_signature=verified|failed|not_available
final_query_status=paid|closed|expired|pending|not_found|unknown
close_result_trusted=true|false
```

建议新增或复用以下固定原因码：

```text
FINAL_QUERY_PAID
FINAL_QUERY_CONFIRMED_CLOSED
FINAL_QUERY_PENDING_TIMEOUT_CLOSED
FINAL_QUERY_NOT_FOUND_TIMEOUT_CLOSED
FINAL_QUERY_UNKNOWN_TIMEOUT_CLOSED
FINAL_QUERY_FAILED_TIMEOUT_CLOSED
FINAL_CLOSE_SKIPPED_CALLBACK_IN_PROGRESS
```

日志仍禁止包含完整订单号、完整响应体、`msg` 原文、签名、密钥、Cookie、Token 或 `pay_info`。

### 8.5 单独排查关单响应验签

相关代码：

```text
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImpl.java
ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentLifecycleDiagnostics.java
```

六号文档显示关单响应字段为：

```text
code
msg
timestamp
sign
sign_type
```

下一上下文需要在不记录原值的前提下，把当前合并的 `LIUHAO_SIGNATURE_INVALID` 细分为低基数原因：

```text
SIGN_TYPE_MISSING
SIGN_TYPE_UNEXPECTED
SIGN_MISSING
SIGN_BASE64_INVALID
CANONICAL_FIELDS_UNEXPECTED
PLATFORM_SIGNATURE_MISMATCH
TIMESTAMP_INVALID
```

排查顺序：

1. 确认关闭接口真实 HTTP 状态和响应 `Content-Type`。
2. 只记录响应字段名集合或白名单布尔值，不记录字段值和原始响应体。
3. 确认关单失败响应是否也带有效 RSA 签名。
4. 确认验签规范仍为排除 `sign`、`sign_type` 和空值后按 ASCII 排序。
5. 确认 `code` 的数字/字符串表示是否影响规范串。
6. 确认 `msg` 中空格、Unicode、转义和 URL 编码是否使用解析后的原值参与验签。
7. 对照六号提供的原始签名样本编写离线单元测试，不在日志中打印样本签名和密钥。

统一下单和查询已经能使用同一平台公钥验签，因此“平台公钥整体配置错误”的可能性较低，但尚不能据此排除关单接口使用了不同签名字段或失败响应未签名。不得在没有样本证据时直接修改 RSA 算法或跳过验签。

## 9. 必须补充的测试代码

第一阶段可以编写但不要自动执行测试：

1. 关单响应 `UNKNOWN` 时，最终边界仍然调用 `queryPayment()`。
2. 关单验签失败时，最终查询返回 `PAID`，必须执行 `CLOSING → PAID`。
3. 关单验签失败时，最终查询返回 `CLOSED/EXPIRED`，必须执行 `CLOSING → CLOSED`。
4. 最终查询返回 `PENDING/NOT_FOUND/UNKNOWN`，按本地超时策略进入 `CLOSED`。
5. 最终查询自身失败时，按用户选择的 fail-closed 策略进入 `CLOSED` 并记录未确认原因。
6. callback marker 在最终 CAS 前出现时，禁止强制关闭。
7. 并发回调已迁移为 `PAID` 时，关闭消费者不得覆盖状态。
8. 重复最终消息保持幂等，不重复结算、退款或发布无界消息。
9. `followup_query` 日志不得再把“跳过”记录为“失败”。
10. 关单验签诊断不得输出签名、密钥、完整响应、完整订单号或 `msg` 原文。

建议新增消费者测试文件，或在现有支付 RabbitMQ 测试目录中补充：

```text
ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/rabbit/MembershipClosingCheckConsumerServiceImplTest.java
```

并扩展：

```text
ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/provider/liuhao/impl/LiuhaoPaymentRestClientImplTest.java
```

## 10. 实施边界

本轮后续实现默认遵守：

- 不新增数据库字段或迁移。
- 不修改 `provider_trade_no` 的 `LIUHAO:ORDER` / `LIUHAO:TRADE` 格式。
- 不新增 RabbitMQ Exchange、Queue 或消息类型。
- 不修改现有五分钟 `CLOSING` 回调窗口。
- 保留 `CLOSING → PAID`。
- 不修改前端和 Cloudflare。
- 不绕过六号响应验签。
- 不把“本地超时 `CLOSED`”伪装成“六号已确认关闭”。

## 11. 验收时间线

```text
订单未支付且进入关闭流程
→ PENDING_PAYMENT → CLOSING
→ 立即尝试六号关单
→ 关单验签失败可以保持 CLOSING
→ 五分钟窗口继续接受在途支付回调
→ 到 closingDeadlineAt 再尝试关单
→ 不论关单结果如何，都独立反查六号订单

反查 PAID
→ CLOSING → PAID

反查 CLOSED/EXPIRED
→ CLOSING → CLOSED（Provider confirmed）

反查 PENDING/NOT_FOUND/UNKNOWN/FAILED
→ CLOSING → CLOSED（timeout unconfirmed）

任何分支
→ 不得永久停留 CLOSING
```

## 12. 给下一上下文的直接任务描述

可以将下面这段直接交给下一上下文：

> 请先阅读 `docs/handoffs/2026-08-30-liuhao-closing-final-convergence-handoff.md`。只修改后端，不新增数据库字段、迁移或 RabbitMQ 拓扑。修复 `MembershipClosingCheckConsumerServiceImpl`：最终边界不能因为 `closePayment()` 返回 `UNKNOWN` 或验签失败而跳过独立 `queryPayment()`；查询为 `PAID` 时进入 `PAID`，查询为 `CLOSED/EXPIRED` 时确认进入 `CLOSED`，查询仍为 `PENDING/NOT_FOUND/UNKNOWN/FAILED` 时按用户已确认的本地超时策略进入 `CLOSED`，并通过日志明确标记为 `timeout unconfirmed`。保留 callback marker 与并发 CAS 防护，禁止覆盖已支付状态。同时细分六号关单响应验签失败的低基数诊断原因，但不得记录完整响应、签名、密钥或订单号。第一阶段只编写代码和测试代码，不运行测试、编译、构建、真实请求或部署。

## 13. 当前验证状态

本文结论来自：

- 用户提供的 2026-08-30 应用日志；
- 对现有关单消费者、六号 REST 客户端和生命周期诊断代码的只读核对；
- 六号订单关闭文档中列出的请求与响应字段。

本文档创建过程中没有证明六号真实关单是否成功，也没有执行任何真实 Provider 请求。
