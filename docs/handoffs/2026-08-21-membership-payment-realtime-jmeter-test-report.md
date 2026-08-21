# 会员模拟支付真实链路 JMeter 测试报告

测试日期：2026-08-21。

测试项目：`C:\Users\damn\Desktop\ai-temperate-main`。

测试模式：`loadtest-realtime`，真实执行 `PENDING_PAYMENT 5 分钟 + CLOSING 5 分钟`，未使用 `loadtest-fast` 压缩时间。

> 本报告记录本机会员模拟支付链路的 JMeter、PostgreSQL、Redis 与 RabbitMQ 联合验收结果。这里的“真实链路”是指真实应用、真实 5+5 分钟时序以及真实 PostgreSQL、Redis、RabbitMQ 异步链路；支付回调仍由受控模拟支付入口产生，不代表接入真实六号支付。

## 一、执行结论

七层正式 Runner 共留下 27 次带 `verdict.json` 的执行记录，其中 16 次用于发现问题和迭代修正，11 次最终验收通过。此外还单独执行了 1 次 `REJECTED-CLOSE-PROBE` 专项探针，用于验证无效成功回调不会再让订单永久停留在 CLOSING。

最终验收结论如下：

- 七个逻辑测试层全部取得通过结果。
- 并发幂等层分别以 `1、10、50、100、500` 五个并发度运行，因此最终通过的是 11 个 Runner，而不是只有 7 个进程。
- 11 个最终通过 Runner 的 `jmeterExitCode` 均为 `0`，并且 `sqlVerified`、`redisVerified`、`rabbitVerified` 均为 `true`。
- 最终通过批次共记录 2,900 条场景订单；所有成功、失败重跑和专项探针的 `scenario-orders.csv` 共记录 11,494 个互不重复的订单 ID。
- X-01 的取消/支付发起竞态已经在新链路中修复并通过回归。
- T-01～T-04 暴露的 `REJECTED → provider-result 缺失 → UNKNOWN → DLQ → 永久 CLOSING` 链路已经修复，新产生的 REJECTED 订单能够继续收敛。
- 修复前遗留的 X-01 与 T-01～T-04 共 5 笔历史订单仍保留在 PostgreSQL 和 Redis 中，未做数据回填或强制清理；因此准确结论是“新链路修复验证通过，历史五笔数据未修复”。
- 没有连接真实六号支付，没有升级会员权益，没有调用真实退款平台，也没有引入 Redis Stream。

## 二、测试体系与数据流

本次测试不是只判断 HTTP 是否返回成功，而是同时检查以下链路：

```text
JMeter HTTP 请求
→ Spring Security / 会员订单接口 / 模拟支付回调接口
→ Redis 订单快照、模拟支付方结果、callback ready/processing、dirty/processing
→ RabbitMQ PENDING/CLOSING 分段检查与有限重试
→ PostgreSQL membership_order / membership_payment_callback
→ SQL、Redis、RabbitMQ 最终状态验收
→ verdict.json
```

任意 HTTP 业务断言、PostgreSQL 事实、Redis 收敛或 RabbitMQ 基线不符合预期，Runner 都必须返回非零退出码，不能仅凭接口连通就判定通过。

### 2.1 七层测试范围

| 测试层 | 案例规模 | 主要覆盖范围 | 最终结果 |
| --- | ---: | --- | --- |
| 认证与路径边界 | 8 类认证场景 | 白名单 AT、过期/伪造 Token、非白名单用户、资源越权、路径隔离、回调认证隔离、关闭测试开关 | PASS |
| 订单状态机 | 25 个真实时间场景 | PENDING_PAYMENT、CLOSING、CANCELLED、CLOSED、PAID，各组至少 5 例 | PASS |
| 回调协议 | 15 个场景 | GET、POST Form、POST JSON、混合重放、缺失/重复/未知字段、错误签名/商户/金额/支付方式、超大请求、不支持方法 | PASS |
| 并发、唯一性与竞态 | 5 个并发度，每轮 6 类身份组合 | 同订单同流水、同订单不同流水、不同订单同流水、不同协议、取消/关单竞态 | 1/10/50/100/500 全部 PASS |
| RabbitMQ 时间状态 | 6 个场景 | PENDING 分段、进入 CLOSING、最终 CLOSED、手动 ACK、有限重试、DLQ、callback marker 保护 | PASS |
| Redis/PostgreSQL 批量持久化 | 6 个规模 | `1、99、100、101、500、2000`，覆盖批次边界、版本单调性与多批收敛 | PASS |
| 恢复与终态清理 | 7 个场景 | callback processing、dirty processing、提交后 Redis complete 中断、PAID/CANCELLED/CLOSED 清理、数据库幂等兜底 | PASS |

认证边界 Runner 在一个 JMeter 业务采样器内编排 8 类认证断言，因此其 `scenario-orders.csv` 只有 1 条准备订单；该行数不能误当成认证测试只有 1 个案例。

### 2.2 最终通过 Runner

| 测试层 | 最终通过目录 | 场景订单行数 |
| --- | --- | ---: |
| 认证边界 | `20260821-102448-loadtest-realtime-membership-auth-boundary` | 1 |
| 状态机 | `20260821-114619-loadtest-realtime-membership-order-state-machine` | 25 |
| 回调协议 | `20260821-120425-loadtest-realtime-membership-callback-transport` | 15 |
| 并发 1 | `20260821-131141-loadtest-realtime-membership-callback-race-idempotency-c1` | 9 |
| 并发 10 | `20260821-132822-loadtest-realtime-membership-callback-race-idempotency-c10` | 9 |
| 并发 50 | `20260821-134507-loadtest-realtime-membership-callback-race-idempotency-c50` | 9 |
| 并发 100 | `20260821-140155-loadtest-realtime-membership-callback-race-idempotency-c100` | 9 |
| 并发 500 | `20260821-144902-loadtest-realtime-membership-callback-race-idempotency-c500` | 9 |
| RabbitMQ | `20260821-150621-loadtest-realtime-membership-rabbit-state-timing` | 6 |
| 批量持久化 | `20260821-153036-loadtest-realtime-membership-persistence-batch` | 2,801 |
| 恢复与清理 | `20260821-155151-loadtest-realtime-membership-recovery-terminal-cleanup` | 7 |

行数合计：

```text
1 + 25 + 15 + (9 × 5) + 6 + 2801 + 7 = 2900
```

### 2.3 迭代执行情况

| 测试层 | 总执行次数 | 最终 PASS | 迭代 FAIL |
| --- | ---: | ---: | ---: |
| 认证边界 | 3 | 1 | 2 |
| 状态机 | 6 | 1 | 5 |
| 回调协议 | 3 | 1 | 2 |
| 并发幂等 | 8 | 5 | 3 |
| RabbitMQ | 1 | 1 | 0 |
| 批量持久化 | 4 | 1 | 3 |
| 恢复与清理 | 2 | 1 | 1 |
| **合计** | **27** | **11** | **16** |

这些 FAIL 并不全部等于产品业务缺陷。迭代中同时出现了业务状态机问题、JMeter 测试数据时间精度问题、断言/Runner 验收问题和受控故障测试未收敛等情况。本报告只把已经有明确业务因果链和修复证据的问题列为产品逻辑缺陷。

## 三、真实 5+5 分钟状态机边界

硬截止时间固定为：

```text
hardCloseAt = expiresAt + 5 minutes
```

服务端按照实际 `receivedAt` 判定回调所属时间区间：

| 时间条件 | 业务区间 | 预期处理 |
| --- | --- | --- |
| `receivedAt < expiresAt` | PENDING_PAYMENT | 已在过期前发起支付时允许收敛为 PAID |
| `expiresAt <= receivedAt < hardCloseAt` | CLOSING | 已在过期前发起支付时仍允许收敛为 PAID |
| `receivedAt >= hardCloseAt` | CLOSED/迟到成功 | 不逆转终态，首次事实记录为 REFUND_REQUIRED |

同时必须满足：

```text
paymentStartedAt < expiresAt
paidAt >= paymentStartedAt
paidAt <= receivedAt
```

### 3.1 PENDING_PAYMENT

五个成功回调分别位于创建后约 5 秒、1 分钟、2 分 30 秒、`expiresAt - 10 秒` 和 `expiresAt - 1 秒`，并混合使用 GET、POST Form、POST JSON。最终均要求订单为 PAID、callback 为 APPLIED。

### 3.2 CLOSING

五个成功回调分别位于：

- `expiresAt + 1 秒`
- `expiresAt + 10 秒`
- `expiresAt + 2 分 30 秒`
- `hardCloseAt - 10 秒`
- `hardCloseAt - 1 秒`

这些用例直接验证了“进入 CLOSING 后仍可接收过期前已经发起的支付结果”，而不是只测试 PENDING 或硬截止之后退款。最后一秒案例还验证 callback marker 能阻止关单任务抢先把订单关闭。

### 3.3 CANCELLED 与 CLOSED

- CANCELLED/CLOSED 收到首次合法成功回调时保持原订单终态，并产生唯一 `REFUND_REQUIRED`。
- 同订单、同流水号后续重放只返回 `200 success`，不新增 callback、不重复触发退款条件。
- 同订单、多个不同流水号并发时只能有一个 callback 事实获胜。

### 3.4 PAID

- 相同流水号的 GET、Form、JSON 顺序或并发重放均返回 `200 success`。
- 不新增 `ALREADY_APPLIED` 数据行。
- 不产生 `REFUND_REQUIRED`。
- 不覆盖订单原有 `provider_trade_no`。
- PAID 后使用不同第三方流水号回调，也必须被订单唯一性拦截，不能再制造“一笔 PAID 订单对应大量退款条件”。

## 四、PostgreSQL 数据量及一万多条记录来源

以下数据是 `2026-08-21 16:44:18 -05:00` 对本机测试库的观察快照。Runner 默认不清理测试订单，后续人工删除、TTL 到期或再次运行测试都会改变实时数量。

### 4.1 当前表数据量

| 项目 | 数量 |
| --- | ---: |
| `membership_order` | 11,523 |
| `membership_payment_callback` | 11,463 |
| callback `APPLIED` | 8,607 |
| callback `REFUND_REQUIRED` | 69 |
| callback `REJECTED` | 2,787 |
| 订单 PENDING_PAYMENT | 0 |
| 订单 CLOSING | 5 |
| 订单 PAID | 8,607 |
| 订单 CANCELLED | 80 |
| 订单 CLOSED | 2,831 |

订单比 callback 多 60 条。这是正常差异：认证准备订单、未产生支付通知的取消/关闭场景以及部分负向案例不一定产生 callback 事实，`membership_order` 与 `membership_payment_callback` 不是严格的一比一全量镜像。

### 4.2 11,463 条 callback 的精确来源

按 `provider_trade_no` 测试前缀统计：

| 前缀 | callback 数量 | APPLIED | REJECTED | REFUND_REQUIRED | 来源 |
| --- | ---: | ---: | ---: | ---: | --- |
| `B2000` | 8,000 | 6,013 | 1,987 | 0 | 2,000 条批量案例执行四轮 |
| `B500` | 2,000 | 1,506 | 494 | 0 | 500 条批量案例执行四轮 |
| `B101` | 404 | 305 | 99 | 0 | 101 条批量案例执行四轮 |
| `B100` | 400 | 301 | 99 | 0 | 100 条批量案例执行四轮 |
| `B99` | 396 | 299 | 97 | 0 | 99 条批量案例执行四轮 |
| `B1` | 4 | 4 | 0 | 0 | 1 条批量案例执行四轮 |
| `JMX` | 153 | 92 | 1 | 60 | 状态机及相关重跑 |
| `RACE` | 76 | 67 | 0 | 9 | 并发、唯一性和竞态测试 |
| `TRANSPORT` | 17 | 8 | 9 | 0 | 回调协议测试 |
| `REC` | 12 | 11 | 1 | 0 | 恢复与终态清理测试 |
| `RABBIT` | 1 | 1 | 0 | 0 | RabbitMQ 时间状态测试 |
| **合计** | **11,463** | **8,607** | **2,787** | **69** |  |

批量持久化层每轮固定产生：

```text
1 + 99 + 100 + 101 + 500 + 2000 = 2801
```

该层因前三轮迭代失败和最终一轮通过，共实际执行四轮：

```text
4 × 2801 = 11204
```

因此，11,463 条 callback 中有 11,204 条来自四轮批量持久化测试，占绝大多数。这一万多条记录是人为构造的批量边界、版本和收敛测试数据，不是正常会员支付业务自然产生的订单量。

### 4.3 数据库唯一性与关系检查

本次验收同时检查：

```sql
SELECT order_id, COUNT(*)
FROM membership_payment_callback
GROUP BY order_id
HAVING COUNT(*) > 1;

SELECT provider_trade_no, COUNT(*)
FROM membership_payment_callback
GROUP BY provider_trade_no
HAVING COUNT(*) > 1;

SELECT COUNT(*)
FROM membership_payment_callback callback
LEFT JOIN membership_order payment_order
       ON payment_order.id = callback.order_id
WHERE payment_order.id IS NULL;
```

当前结果分别为：重复 `order_id = 0`、重复 `provider_trade_no = 0`、孤儿 callback `= 0`。

数据库最终幂等由以下唯一性共同保障：

- `membership_payment_callback.order_id` 唯一。
- `membership_payment_callback.provider_trade_no` 唯一。
- `membership_order.provider_trade_no` 通过非空值唯一 B-tree 索引约束。

Redis 的订单级和第三方流水级原子判断只能做快速拦截，不能替代这些 PostgreSQL 最终事实约束。

## 五、Redis 中 11,596 个 provider-result 的来源

### 5.1 它是什么

Key 模式：

```text
ait:prod:payment:provider-result:v1:status:{orderId}
```

它是“模拟支付平台针对一笔订单的主动查询结果”，类型为 Redis Hash。它不是 `membership_payment_callback` 表的 Redis 镜像，也不是 callback 队列本身。

Hash 字段固定为：

```text
schemaVersion
orderId
status
callbackId
providerTradeNo
payType
paidAmountYuan
updatedAt
```

订单创建时初始化为 `UNPAID`；合法成功通知进入队列时原子更新为 `PAID`；无效成功通知处理完成后应重置为 `UNPAID`，供最终软关单明确判断。

### 5.2 当前 Key 数量与编码组成

| 项目 | 数量 |
| --- | ---: |
| provider-result 总数 | 11,596 |
| 当前 22 位 Base64URL ID | 11,533 |
| 修复前 26 位 ULID | 63 |
| Redis 有、PostgreSQL 已无对应订单 | 83 |
| 其中旧 26 位 ULID | 63 |
| 其中 22 位 Base64URL 残留 | 20 |
| PostgreSQL 有订单、Redis provider-result 已无 | 10 |

Redis 比 PostgreSQL 订单表净多 73 个 provider-result，但净差不能简单解释为“多创建了 73 笔订单”：

```text
Redis 无数据库对应：83
数据库无 Redis 对应：10
净差：83 - 10 = 73
```

83 个 Redis 残留包括 63 个迁移为 Base64URL 前生成的 ULID Key，以及 20 个失败重跑或数据库记录被删除后仍处于 TTL 内的 Base64URL Key。数据库中另有 10 笔订单，其 provider-result 已经随终态清理或 TTL 生命周期消失。

出现这些残留的主要原因是：

- 所有正式 Runner 都记录 `cleanupPerformed=false`。
- 同一天反复执行了状态机、协议、并发和四轮大批量测试。
- 测试期间曾人工删除 PostgreSQL 测试记录，但没有同步删除 Redis Key。
- 测试时这些 Hash 采用较长 TTL，旧 Key 不会因数据库行删除而自动立即消失。

### 5.3 其他 Redis 结构的测试含义

| 结构 | 类型 | 成员/排序语义 |
| --- | --- | --- |
| 订单快照 | Hash | 保存订单实时状态、时间边界和 `stateVersion` |
| callback ready | 全局 ZSet | member 为 `callbackId`，score 为允许领取的时间戳 |
| callback processing | 全局 ZSet | member 为 `callbackId`，score 为领取时间，用于 processing 超时恢复 |
| order-persist dirty | 全局 ZSet | member 为 `orderId#stateVersion`，score 为状态变化时间 |
| order-persist processing | 全局 ZSet | member 为 `orderId#stateVersion`，score 为领取时间，用于超时恢复 |

ZSet 不是按订单字符串排序，而是首先按数值 score 排序；score 相同才按 member 字典序稳定排列。processing 完成脚本还会比对预期 score，避免旧 Worker 清理后来 Worker 已经重新领取的任务。

## 六、X-01：取消与支付发起竞态

### 6.1 现象

修复前的 X-01 订单：

```text
AaAlGPi_AQGh5kxhTLLv_g
```

取消操作已经在 Redis 形成 CANCELLED 终态，但支付发起事务仍可能依据数据库中的旧 PENDING_PAYMENT 状态成功写入。Redis 与 PostgreSQL 因而可能出现相同 `stateVersion`、不同状态的分叉。后续检查消息又按旧数据库事实继续推进，使本应取消的订单进入 CLOSING。

### 6.2 根因

旧支付发起顺序缺少两个实时状态门禁：

1. PostgreSQL 条件更新前没有先拒绝 Redis 中已经明确的 PAID/CANCELLED/CLOSED。
2. PostgreSQL 提交并刷新 Redis 后，没有再次读取可能已被并发回调或取消推进到更高版本的实时快照。

因此，数据库条件 UPDATE 虽然自身原子，却无法独立解决“Redis 实时状态先于批量数据库落库”的业务竞态。

### 6.3 修复

`MembershipPaymentAttemptServiceImpl` 现在执行：

```text
读取 Redis 实时快照
→ 终态或过期立即拒绝
→ PostgreSQL 本地事务记录支付发起事实
→ 按单调 stateVersion 刷新 Redis
→ 再次读取 Redis 胜出快照
→ 再次拒绝并发产生的 PAID/CANCELLED/CLOSED
```

对应实现证据：

- [`MembershipPaymentAttemptServiceImpl.java`](../../ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentAttemptServiceImpl.java)

### 6.4 修复后验证

最终状态机 Runner 中的 X-01：

| 字段 | 实际值 |
| --- | --- |
| Runner | `20260821-114619-loadtest-realtime-membership-order-state-machine` |
| 新订单 ID | `AaAlN3P3AQE9-nKBAS-B7w` |
| 目标状态 | CANCELLED |
| 最终状态 | CANCELLED |
| callback resolution | REFUND_REQUIRED |
| 目标与实际回调漂移 | 15ms |
| JMeter/SQL/Redis/RabbitMQ | 全部通过 |

这证明新产生的 X-01 竞态订单不会再被支付发起流程复活。

## 七、T-01～T-04 与 REJECTED 后永久 CLOSING

### 7.1 首个问题：JMeter 时间精度

T-01～T-04 最初把 `paidAt` 构造成整秒，而服务端 `paymentStartedAt` 带毫秒或更高精度，可能出现：

```text
paidAt            = 11:59:02.000
paymentStartedAt  = 11:59:02.353
```

此时 `paidAt < paymentStartedAt`，回调裁决为 REJECTED 是正确业务行为，不是状态机误判。JMeter 后续调整了合法成功场景的时间构造，确保 `paidAt >= paymentStartedAt`，避免测试数据自己违反支付时序。

### 7.2 真正的产品逻辑缺陷

REJECTED 本身合理，但旧的回调完成动作会删除模拟支付方结果：

```text
回调 REJECTED
→ callback complete 删除 provider-result
→ 订单达到 hardCloseAt 后主动查询支付方
→ provider-result 不存在，查询结果为 UNKNOWN
→ 三次有限重试均为 UNKNOWN
→ closing check 消息进入 DLQ
→ 订单为避免误关单而保持 CLOSING
```

UNKNOWN 不能当作 UNPAID 直接关单，这个安全原则本身正确；错误在于应用自己把一个明确的无效成功结果删除成了 UNKNOWN，破坏了最终关单所需的明确事实。

### 7.3 修复方式

回调批处理对 REJECTED 使用：

```text
PaymentProviderResultCompletionAction.RESET_UNPAID
```

`callback_complete.lua` 只有在 provider-result 仍属于当前 callback 时才原子执行：

```text
status = UNPAID
callbackId = 空
providerTradeNo = 空
payType = 空
paidAmountYuan = 空
```

这样既不会把无效成功通知保留成 PAID，也不会把明确未支付事实删除成 UNKNOWN。CLOSING 最终检查能够取得明确 UNPAID，并安全迁移到 CLOSED。

对应实现证据：

- [`PaymentCallbackBatchServiceImpl.java`](../../ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/callback/impl/PaymentCallbackBatchServiceImpl.java)
- [`callback_complete.lua`](../../ai-temperate-service/src/main/resources/lua/membership-payment/callback_complete.lua)

### 7.4 专项探针

专项目录：

- [`20260821-123856-rejected-close-probe`](../../loadtest-output/runs/20260821-123856-rejected-close-probe/)

结果：

| 项目 | 实际值 |
| --- | --- |
| JMeter elapsed | 602,093ms，约 10 分 2 秒 |
| HTTP | 200 |
| JMeter success | true |
| callback resolution | REJECTED |
| 订单最终状态 | CLOSED |

该探针直接覆盖“回调被拒绝，但订单仍必须在硬截止后正常关闭”的原始故障路径。

### 7.5 PostgreSQL 修复前后对照

当前 REJECTED callback 关联的订单状态为：

| 订单状态 | 数量 | 含义 |
| --- | ---: | --- |
| CLOSING | 5 | 全部为修复前的历史卡单 |
| CANCELLED | 3 | 取消订单保持终态 |
| CLOSED | 2,779 | 修复后能够继续收敛的主要批量案例 |

仍停留在 CLOSING 的五笔历史订单为：

| 来源 | 订单 ID | callback resolution | 当前状态 |
| --- | --- | --- | --- |
| X-01 | `AaAlGPi_AQGh5kxhTLLv_g` | REJECTED | CLOSING |
| T-01 | `AaAlQv0lAQGr03EdNKUvYA` | REJECTED | CLOSING |
| T-02 | `AaAlQv0pAQGlCdAiWI4eKg` | REJECTED | CLOSING |
| T-03 | `AaAlQv0sAQEoQa1fifAU8w` | REJECTED | CLOSING |
| T-04 | `AaAlQv0wAQECy8GU64fisA` | REJECTED | CLOSING |

这五笔记录用于保留故障证据，没有执行数据库状态修复、RabbitMQ 补发或 Redis 强制清理。它们不能用于否定修复后的新链路，也不能被描述成已经自动恢复。

## 八、其他逻辑问题与验证结果

### 8.1 callback 订单级唯一性

早期设计只依赖第三方流水唯一时，同一业务订单可以用不同 `provider_trade_no` 写入多条 callback，曾出现一笔 PAID 订单关联大量后续回调事实的问题。

当前约束为：

- 一个 `membership_payment_callback.order_id` 最多一行。
- 一个 `membership_payment_callback.provider_trade_no` 最多一行。
- 相同订单或相同流水的合法重复通知返回 `200 success`，但内部不重新入队、不新增 callback、不更新订单、不重复触发退款条件。
- Redis Lua 同时做订单级和第三方流水级快速拦截；即使 Redis 幂等 Key 过期或被清理，PostgreSQL 唯一约束仍为最终裁决。

对应 Schema 证据：

- [`018_create_membership_order.sql`](../../sql/018_create_membership_order.sql)
- [`019_create_membership_payment_callback.sql`](../../sql/019_create_membership_payment_callback.sql)
- [`028_upgrade_membership_payment_closure.sql`](../../sql/migrations/028_upgrade_membership_payment_closure.sql)

### 8.2 PAID 后重复回调

并发层验证了：

- 同订单、同流水、同载荷顺序重复。
- 同订单、同流水并发重复。
- 同订单、同流水、不同非关键载荷和不同协议。
- 同订单、不同流水并发。
- 不同订单复用同一流水并发。

最终结果满足：

```text
订单保持 PAID
原 callback 保持 APPLIED
callback 总数为 1
原 provider_trade_no 不被覆盖
无新 ALREADY_APPLIED 行
无 REFUND_REQUIRED
合法重复请求均为 200 success
```

### 8.3 CANCELLED/CLOSED 后回调

CANCELLED/CLOSED 的首次合法成功回调允许留下唯一支付事实，并裁决为 `REFUND_REQUIRED`；后续相同或不同协议重放只返回成功确认，不重复触发退款条件。退款范围仅为 resolution、固定事件和低基数指标，不调用真实退款平台。

### 8.4 不同订单复用同一第三方流水

并发测试证明第三方流水只能绑定一个订单。第二个订单复用已有流水时不能被错误标为 PAID，也不能覆盖第一笔数据库事实。

### 8.5 没有确认的其他状态机逻辑缺陷

最终七层 Runner 和专项探针没有再发现新的、可复现的状态机业务逻辑错误。测试过程中还发现并修正过 JMeter 时间生成、断言和 Runner 验收问题，但这些测试基础设施问题不应混写成产品状态机 Bug。

## 九、RabbitMQ、批量持久化与恢复结论

### 9.1 RabbitMQ

已验证：

- PENDING 和 CLOSING 使用真实生产 5+5 分钟分段时序。
- 消费者手动 ACK，业务成功后才确认消息。
- UNKNOWN/受控失败使用有限次数重试，不无限 requeue。
- 重试耗尽进入预期 DLQ。
- callback marker 存在时不会错误关闭订单。
- 最终 Ready、Unacked、DLQ 与运行前基线符合场景预期。
- 没有产生 Redis Stream。

### 9.2 批量持久化

完整链路经过：

```text
callback ready
→ callback processing
→ PostgreSQL callback 唯一插入或幂等命中
→ Redis 订单状态迁移
→ dirty ZSet
→ PostgreSQL 批量状态更新
```

`99/100/101` 验证批次边界，500 验证单批上限，2,000 验证多批收敛。批内相同 `order_id` 或相同 `provider_trade_no` 只能由第一条事实获胜，旧 `stateVersion` 不得覆盖新状态。

### 9.3 恢复与终态清理

已覆盖：

- callback processing 超时恢复。
- dirty processing 超时恢复。
- PostgreSQL 已提交、Redis complete 未执行时的幂等重试。
- PAID、CANCELLED、CLOSED 持久化后的订单快照清理。
- Redis 幂等 Key 失效后由 PostgreSQL 唯一约束兜底。

最终通过批次中，终态订单不会因旧快照长期重新覆盖数据库的新版本。

## 十、证据索引

### 10.1 最终 Runner

每个正式 Runner 目录都包含相应 JTL、`jmeter.log`、`scenario-orders.csv`、`summary.csv`、`sql-verification.txt`、`redis-verification.json`、`rabbit-verification.json` 和 `verdict.json`。

- [认证边界](../../loadtest-output/runs/20260821-102448-loadtest-realtime-membership-auth-boundary/)
- [订单状态机](../../loadtest-output/runs/20260821-114619-loadtest-realtime-membership-order-state-machine/)
- [回调协议](../../loadtest-output/runs/20260821-120425-loadtest-realtime-membership-callback-transport/)
- [并发 1](../../loadtest-output/runs/20260821-131141-loadtest-realtime-membership-callback-race-idempotency-c1/)
- [并发 10](../../loadtest-output/runs/20260821-132822-loadtest-realtime-membership-callback-race-idempotency-c10/)
- [并发 50](../../loadtest-output/runs/20260821-134507-loadtest-realtime-membership-callback-race-idempotency-c50/)
- [并发 100](../../loadtest-output/runs/20260821-140155-loadtest-realtime-membership-callback-race-idempotency-c100/)
- [并发 500](../../loadtest-output/runs/20260821-144902-loadtest-realtime-membership-callback-race-idempotency-c500/)
- [RabbitMQ 时间状态](../../loadtest-output/runs/20260821-150621-loadtest-realtime-membership-rabbit-state-timing/)
- [批量持久化](../../loadtest-output/runs/20260821-153036-loadtest-realtime-membership-persistence-batch/)
- [恢复与终态清理](../../loadtest-output/runs/20260821-155151-loadtest-realtime-membership-recovery-terminal-cleanup/)

### 10.2 专项探针

- [REJECTED 后正常关单探针 JTL](../../loadtest-output/runs/20260821-123856-rejected-close-probe/results.jtl)
- [REJECTED 后正常关单场景订单](../../loadtest-output/runs/20260821-123856-rejected-close-probe/scenario-orders.csv)

`loadtest-output` 是本机运行产物目录，可能被 Git 忽略；报告中的链接用于当前工作区复核，不能假设其他克隆环境自动包含这些产物。

## 十一、准确性边界与待验证事项

- 报告没有记录完整 Access Token、callback key、PostgreSQL 密码、Redis 密码或 RabbitMQ 密码。
- 22 位 Base64URL 当前订单 ID 与 26 位旧 ULID Key 已分开统计。
- 报告中的数据库和 Redis 数量是指定观察时间的快照，不是永久固定值。
- X-01、T-01～T-04 的旧五笔 CLOSING 数据尚未执行修复迁移或清理。
- 订单快照、模拟平台结果和 callback 原始数据的 TTL 已在测试完成后从 24 小时调整为 6 小时，但该变更尚未重新编译、重启应用或执行回归；它不计入本报告的 PASS 结论。
- 本报告不宣称 PostgreSQL、Redis 与 RabbitMQ 之间具备分布式事务或 Exactly Once；最终幂等依赖 PostgreSQL 唯一约束，异步链路依赖有限重试、状态版本和 TTL 收敛。

## 十二、最终判定

本次真实 5+5 分钟会员模拟支付测试已经覆盖认证、状态机、回调协议、唯一性与并发、RabbitMQ 时间状态、批量持久化、故障恢复和终态清理七个层次。最终 11 个 Runner 的 HTTP、PostgreSQL、Redis 与 RabbitMQ 验收同时通过。

X-01 的取消/支付发起竞态，以及 T-01～T-04 共同暴露的 REJECTED 后 provider-result 被删除而永久 CLOSING 的问题，均已在新代码路径中修复并通过真实时间回归。修复前五笔历史订单仍作为故障证据保留，因此最终结论为：

```text
新链路修复验证通过；历史五笔卡单未自动修复；TTL 6 小时变更待后续重启与回归验证。
```
