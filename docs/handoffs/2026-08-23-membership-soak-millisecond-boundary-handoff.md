# 会员支付 W01～W08 与 4,000 笔毫秒边界 JMeter 测试交接

## 1. 交接结论

截至 2026-08-23 13:43（America/Chicago），当前结论必须拆成三部分：

1. W01～W07 在正式本地运行中分别取得了独立 `PASS`；W08 在原运行中因校验器问题 `FAIL`，修正校验器后通过单独重跑取得了 `PASS`。
2. `NOT_GRANTED` 与 `REFUND_REQUIRED` 各 20 笔的 W08S 专项已经完成并取得 `PASS`。
3. 4,000 笔、8×500 的毫秒边界 JMeter 测试没有完成。目前只执行了第一波 E-PRE 的 1,000 笔，正式运行已停止，状态为 `FAIL`；其余 E-AFTER、H-PRE、H-AFTER 共 3,000 笔尚未执行。

因此目前不能给出“W01～W08 同一构建整体 PASS”，也不能给出“4,000 笔毫秒边界 PASS”。当前正式裁决是：

```text
W01～W07：独立 PASS
W08：修正校验器后的独立重跑 PASS
W08S 20+20：独立 PASS
W01～W08 同构建聚合：尚未证明
4,000 笔毫秒边界：FAIL / STOPPED，待修测试器后重跑
BAR W09～W16：本阶段尚未开始
```

失败证据必须保留。修复后的结果不得与修复前的结果拼接成一次正式 PASS。

## 2. 当前运行状态快照

### 2.1 进程与端口

- 毫秒边界 Runner 已退出。
- JMeter 已退出。
- 本地应用仍运行，Java PID 为 `19132`。
- `http://127.0.0.1:6655/actuator/health/readiness` 返回 HTTP 200。
- 本机没有应用监听 `8080`。
- 回环地址上只有一个 `6655` 应用实例；另有 Windows `svchost.exe` 在 `10.66.0.2:6655` 持有系统网络监听，不是第二个 Java 应用实例，也不在 Runner 允许的回环监听集合内。

当前测试没有在后台继续推进。若数据库数据发生变化，只可能来自仍存活的 6655 应用异步任务，而不是当前 Runner 或 JMeter。

### 2.2 PostgreSQL 当前固定测试数据

固定用户范围：

```text
70000000000000000 ～ 70000000000003999
```

当前数据库实测：

| 指标 | 数量 |
| --- | ---: |
| 固定登录身份 | 4,000 |
| 固定用户资料 | 4,000 |
| 固定会员额度行 | 4,000 |
| 最新失败运行订单 | 1,000 |
| 最新失败运行回调 | 1,000 |
| 活动订单 | 0 |
| PAID + APPLIED | 1,000 |
| 未决权益 | 0 |
| 未解析回调 | 0 |

当前 4,000 个额度模板的等级分布：

| membership_tier | 业务等级 | 数量 |
| ---: | --- | ---: |
| 0 | FREE | 3,000 |
| 1 | GO | 250 |
| 4 | PLUS | 250 |
| 5 | PRO | 250 |
| 6 | MAX | 250 |

这 1,000 个非 FREE 用户来自失败运行 E-PRE 的真实成功支付。失败证据尚未清理，因此暂时保留是正确行为。下一次正式重跑前应按本轮 1,000 个公开订单 ID 精确重置支付事实和额度；固定 4,000 个身份、资料与额度模板不得删除。

### 2.3 Redis 与 RabbitMQ 当前状态

通过 6655 回环只读检查接口，对最新 E-PRE 的 1,000 个订单逐批检查：

```text
Redis order snapshot present = 0
Redis callback marker present = 0
callback ready = 0
callback processing = 0
dirty = 0
dirty processing = 0
```

RabbitMQ 当前实测：

| Queue | Consumers | Ready | Unacked | 类型 |
| --- | ---: | ---: | ---: | --- |
| membership.payment.check.queue | 1 | 0 | 0 | quorum |
| membership.closing.check.queue | 1 | 0 | 0 | quorum |
| membership.payment.check.dlq | 0 | 0 | 0 | quorum |
| membership.closing.check.dlq | 0 | 0 | 0 | quorum |

所以当前没有永久 PENDING/CLOSING、Marker 残留、异步集合积压或会员队列积压。

## 3. W01～W08 已完成范围

主运行证据根目录：

```text
loadtest-output/soak/membership-payment-20260823-083156/local
```

| 波次 | 场景 | 实际订单 | 结果 | JMeter | SQL | Redis | RabbitMQ | 证据目录 |
| --- | --- | ---: | --- | ---: | --- | --- | --- | --- |
| W01 | 订单状态机、取消、迟到支付与 X-01 基线 | 30 | PASS | 0 | PASS | PASS | PASS | `W01/20260823-083359-loadtest-realtime-membership-order-state-machine` |
| W02 | PENDING 0～8、CLOSING 0～4 全阶段 Marker | 28 | PASS | 0 | PASS | PASS | PASS | `W02/20260823-085501-loadtest-realtime-membership-marker-stage-matrix` |
| W03-A | 回调传输协议 T-01～T-15 | 15 | PASS | 0 | PASS | PASS | PASS | `W03-A/20260823-091631-loadtest-realtime-membership-callback-transport` |
| W03-B | 回调身份、订单号/流水号并发 C10 | 5 | PASS | 0 | PASS | PASS | PASS | `W03-B/20260823-091736-loadtest-realtime-membership-callback-identity-c10` |
| W04 | 单活动订单、幂等键、创建与 Payment Attempt 并发 | 25 | PASS | 0 | PASS | PASS | PASS | `W04/20260823-091851-loadtest-realtime-membership-order-concurrency` |
| W05 | RabbitMQ 时间状态与队列拓扑 | 11 | PASS | 0 | PASS | PASS | PASS | `W05/20260823-092026-loadtest-realtime-membership-rabbit-state-timing` |
| W06 | Worker/回调/刷盘恢复与终态清理 | 12 | PASS | 0 | PASS | PASS | PASS | `W06/20260823-093148-loadtest-realtime-membership-recovery-terminal-cleanup` |
| W07 | REJECTED → CLOSED、永久 CLOSING 专项 | 10 | PASS | 0 | PASS | PASS | PASS | `W07/20260823-100445-loadtest-realtime-membership-rejected-closing-matrix` |
| W08 原运行 | 长观察：未支付、APPLIED、REJECTED | 12 | FAIL | 未形成最终有效裁决 | FAIL | 未记 PASS | 未记 PASS | `W08/20260823-101607-loadtest-realtime-membership-long-observation` |

W08 原运行在 PostgreSQL 校验阶段失败，原 `sql-verification.txt` 只执行到临时表创建，不能把它解释为产品状态机失败。

修正校验器后的 W08 独立证据：

```text
loadtest-output/soak/w08-verifier-fix-20260823-1245/local/W08/
  20260823-103622-loadtest-realtime-membership-long-observation
```

该次重跑创建 12 笔订单，`jmeterExitCode=0`，SQL、Redis、RabbitMQ 均为 `true`，并完成精确清理，最终 `PASS`。其中：

```text
4 笔 APPLIED → PAID
4 笔 REJECTED → CLOSED + NOT_GRANTED
4 笔无回调 → CLOSED + NOT_GRANTED
```

### 3.1 W01～W08 的有效性边界

W01～W07 与修正后的 W08 都有独立可审计 PASS 证据，但旧 Runner 没有把源码指纹写入每个波次的 `run-config.json`。因此当前无法证明它们来自完全相同的源码指纹。

正确表述只能是：

```text
W01～W07 分别通过。
W08 修正校验器后单独通过。
尚未形成一次“同一源码指纹连续执行 W01～W08”的聚合 PASS。
```

若最终报告要求 W01～W08 作为一个正式整体 PASS，必须在毫秒测试器修复并冻结源码后，从 W01 连续重跑到 W08，并为每个波次记录同一 `sourceFingerprint`。

## 4. NOT_GRANTED / REFUND_REQUIRED 20+20 专项

证据目录：

```text
loadtest-output/soak/entitlement-resolution-matrix-20260823-110115/local/W08S/
  20260823-110115-loadtest-realtime-membership-entitlement-resolution-matrix
```

该波次真实创建 40 笔订单，JMeter、SQL、Redis、RabbitMQ 全部通过，并执行精确清理。

### 4.1 NOT_GRANTED：20 笔

| 子场景 | 数量 | 最终状态 | callback resolution | entitlement resolution |
| --- | ---: | --- | --- | --- |
| 有 Payment Attempt、无支付回调 | 5 | CLOSED | 无 callback | NOT_GRANTED |
| 无 Payment Attempt、无支付回调 | 5 | CLOSED | 无 callback | NOT_GRANTED |
| PENDING 阶段收到 REJECTED 回调 | 5 | CLOSED | REJECTED | NOT_GRANTED |
| CLOSING 阶段收到 REJECTED 回调 | 5 | CLOSED | REJECTED | NOT_GRANTED |

### 4.2 REFUND_REQUIRED：20 笔

| 子场景 | 数量 | 最终状态 | callback resolution | entitlement resolution |
| --- | ---: | --- | --- | --- |
| CANCELLED 后迟到支付 | 5 | CANCELLED | REFUND_REQUIRED | REFUND_REQUIRED |
| cancel/pay 回调竞态 | 5 | CANCELLED | REFUND_REQUIRED | REFUND_REQUIRED |
| CLOSED 后迟到支付 | 5 | CLOSED | REFUND_REQUIRED | REFUND_REQUIRED |
| hardCloseAt 后支付 | 5 | CLOSED | REFUND_REQUIRED | REFUND_REQUIRED |

SQL 还确认：同一订单多回调计数异常为 0。

## 5. 4,000 笔毫秒边界 JMeter 规格

所有场景必须由 JMeter 通过真实 HTTP 调用 6655，经过 PostgreSQL、Redis、RabbitMQ、订单状态机和回调链路；不得用单元测试替代正式裁决。

固定配置：

```text
Profile = loadtest-realtime
Application port = 6655
PENDING_PAYMENT = 5 分钟
CLOSING = 5 分钟
hardCloseAt = expiresAt + 5 分钟
固定用户 = 4,000
正式订单 = 4,000
分组 = 8 × 500
```

### 5.1 八个区段

| 区段 | 用户序号 | 参照边界 | 目标发送时刻 | 数量 | 所属波次 |
| --- | --- | --- | --- | ---: | --- |
| E-P1 | 0000～0499 | expiresAt | `expiresAt - 1ms`，500 笔同点并发 | 500 | E-PRE |
| E-PR | 0500～0999 | expiresAt | `-1000ms, -998ms, …, -2ms` | 500 | E-PRE |
| E-A1 | 1000～1499 | expiresAt | `expiresAt + 1ms`，500 笔同点并发 | 500 | E-AFTER |
| E-AR | 1500～1999 | expiresAt | `0ms, +2ms, …, +998ms` | 500 | E-AFTER |
| H-P1 | 2000～2499 | hardCloseAt | `hardCloseAt - 1ms`，500 笔同点并发 | 500 | H-PRE |
| H-PR | 2500～2999 | hardCloseAt | `-1000ms, -998ms, …, -2ms` | 500 | H-PRE |
| H-A1 | 3000～3499 | hardCloseAt | `hardCloseAt + 1ms`，500 笔同点并发 | 500 | H-AFTER |
| H-AR | 3500～3999 | hardCloseAt | `0ms, +2ms, …, +998ms` | 500 | H-AFTER |

每个 500 笔区段中，GO、PLUS、PRO、MAX 各 125 笔。每个 1,000 笔波次另执行 5 个 TEAM 负向探针；TEAM 探针不得创建订单，不计入 4,000 笔成功订单。

### 5.2 正确裁决时间

计划时间 `targetAt` 只用于测量调度漂移，不得直接决定业务结果。最终必须逐笔使用服务端持久化的：

```text
membership_payment_callback.received_at
```

硬关闭边界规则：

```text
receivedAt < hardCloseAt
→ APPLIED / PAID / 发放目标套餐

receivedAt >= hardCloseAt
→ REFUND_REQUIRED / 不发放套餐

receivedAt == hardCloseAt
→ 属于退款侧
```

`-1ms` 同点并发不预设 500 笔全部成功。操作系统、JVM、网络和线程池抖动会使请求落在边界两侧；验收必须按实际 `receivedAt` 分桶。

## 6. 毫秒边界正式运行进度

当前最新运行：

```text
Run ID: membership-millisecond-boundary-20260823-131450
Source fingerprint: 5e491060d2b368ecff1e3f384dac6677ca2d56a6a69a69bb35571c7b70cce580
State: FAIL
Wave: STOPPED
Failure: PostgreSQL server-time verification failed
```

实际进度：

| 波次 | 计划订单 | 执行状态 | 当前裁决 |
| --- | ---: | --- | --- |
| E-PRE | 1,000 | 已创建、已发送 1,000 次回调、已全部终态收敛 | FAIL，不可记 PASS |
| E-AFTER | 1,000 | 未开始 | 未测试 |
| H-PRE | 1,000 | 未开始 | 未测试 |
| H-AFTER | 1,000 | 未开始 | 未测试 |

E-PRE 的产品数据最终为 1,000 笔 `PAID + APPLIED`，没有活动订单、未决权益或回调残留。但这不能转换为波次 PASS，因为测试器本身同时存在两个缺陷。

## 7. 当前已确认的测试器缺陷

### 7.1 JMeter 终态观察并发异常

最新两次 E-PRE 的 `results.jtl` 都记录：

```text
success = false
responseCode = 500
ExecutionException → NullPointerException
Cannot invoke "Object.hashCode()" because "key" is null
```

位置集中在：

```text
loadtest/scripts/jmeter/membership-millisecond-boundary.groovy
```

当前终态观察逻辑使用普通 `HashSet remaining`，随后让多个观察线程并发调用 `remaining.remove(userId)`。该集合不是线程安全集合；异常又发生在通过 `ConcurrentHashMap orders[userId]` 取值的观察阶段，现有证据高度指向并发修改使观察集合产生非法/null 元素。

此外，PowerShell Runner 只检查：

```text
JMeter 进程退出码
scenario-orders.csv 是否 1,000 行
callback-dispatch.csv 是否 1,000 行
```

JMeter 即使有失败采样仍可能返回进程退出码 0，因此 Runner 漏掉了 JTL 中的失败，继续进入 SQL 校验。这是独立的验收门禁缺陷。

修复要求：

1. 先添加可稳定复现的测试器合同测试。
2. 将 `remaining` 改为线程安全集合，或由工作线程只返回已终态 ID，统一在单线程移除。
3. Runner 必须解析 JTL；任何 `success=false`、非预期 responseCode 或 sampler failure 都立即失败。
4. 修复后完整重跑受影响的 E-PRE，不得复用本次 JTL 作为 PASS。

### 7.2 PostgreSQL 校验错误使用 nullable closing_deadline_at

当前错误 SQL：

```text
loadtest/sql/verify-membership-millisecond-boundary-wave.sql
```
错误点：

1. 使用数据库 `membership_order.closing_deadline_at` 判断 `receivedAt` 应为 APPLIED 还是 REFUND_REQUIRED。
2. 强制要求 `closing_deadline_at == scenario-orders.csv` 中计划的 `hard_close_at`。

但订单在 expiresAt 附近支付成功时，可能尚未真正迁移到 CLOSING。终态 PAID 快照会合法保留 `closing_deadline_at = NULL`。因此 1,000 笔被全部标记为：

```text
HARD_CLOSE_AT_CHANGED
```

这不是产品永久 CLOSING 或权益发放错误，而是 SQL 校验器把“计划硬截止”与“订单是否实际进入 CLOSING”混为一谈。

正确修复：

1. 使用 `scenario-orders.csv.planned_hard_close_at` 对 `callback.received_at` 分桶。
2. `closing_deadline_at` 只在订单实际进入 CLOSING 时校验；PAID-before-CLOSING 时允许为 NULL。
3. 不得因为 NULL 把 APPLIED 错判为 REFUND_REQUIRED。
4. 为 PAID-before-CLOSING、PAID-in-CLOSING、exact-hardCloseAt 三种情况增加 SQL 校验器回归测试。

## 8. 历次毫秒测试尝试

| Run ID | 指纹 | 结果 | 失败原因 | 处理状态 |
| --- | --- | --- | --- | --- |
| `...-124840` | `737af3d2...` | FAIL | 4,000 固定 fixture 初始化返回 HTTP 500 | fixture 分批/事务初始化已调整；失败证据保留 |
| `...-125641` | `2956b170...` | FAIL | 创建阶段未生成完整 1,000 订单，确定性幂等 UUID 不满足 UUIDv4 合同 | 已改为确定性 UUIDv4；失败证据保留 |
| `...-130128` | `b60faf50...` | FAIL | JMeter 观察异常；SQL 又在 callback/权益异步收敛前过早执行 | 已加入最长 180 秒、每 2 秒一次的 settlement wait；其余缺陷未完全修复 |
| `...-131450` | `5e491060...` | FAIL | JMeter 观察异常仍存在；SQL 错把 nullable closingDeadlineAt 当计划硬截止 | 当前停止点；待修复后从 E-PRE 重跑 |

## 9. Token 当前状态

固定 4,000 个用户已经真实存在，并非用户模板不足。Token 接口按每页 500 个签发，波次映射为：

```text
E-PRE   → page 0、1  → 1,000 Token
E-AFTER → page 2、3  → 1,000 Token
H-PRE   → page 4、5  → 1,000 Token
H-AFTER → page 6、7  → 1,000 Token
```

当前磁盘只保留三个失败运行的 E-PRE Token 文件，每个文件 1,000 条数据加一行表头：

```text
membership-millisecond-boundary-20260823-125641-E-PRE.csv
membership-millisecond-boundary-20260823-130128-E-PRE.csv
membership-millisecond-boundary-20260823-131450-E-PRE.csv
```

所以“当前没有完整 4,000 Token 文件”是事实；但这不是服务端只能生成 1,000 Token。Runner 原设计是每个波次开始时按页即时签发，仅执行 E-PRE 就不会提前生成后面 3,000 个 Token。

下一次运行不得依赖历史 Token 文件，应在新 Run ID 下重新签发对应页。成功完成全套后删除本轮 Token 分片；固定 4,000 个用户模板继续永久保留。

## 10. paid_at 毫秒精度待办

PostgreSQL 字段是 `TIMESTAMPTZ(6)`，能够保存微秒。当前 `paid_at` 只显示到整秒不是 PostgreSQL 截断，而是 JMeter 与模拟回调解析协议共同造成：

```text
loadtest/scripts/jmeter/membership-millisecond-boundary.groovy
DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

PaymentCallbackReceiveServiceImpl
DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
```

JMeter 当前把 `addtime/endtime` 格式化为整秒，后端再按整秒格式解析，所以数据库只能收到整秒值。

需要注意：业务硬截止裁决使用的是服务端 `receivedAt`，不是 `paidAt`。因此把 `paid_at` 改成毫秒主要提升支付事实和测试证据精度，不应改变硬截止裁决规则。

后续实现顺序：

1. 先为回调时间解析添加合同测试：原整秒格式继续兼容，新增毫秒格式兼容。
2. 将解析器改成可选小数秒，建议接受 0～9 位但测试输出固定 3 位。
3. JMeter 将 `addtime/endtime` 改为 `yyyy-MM-dd HH:mm:ss.SSS`。
4. 在 scenario、callback-dispatch、SQL 证据中同时输出毫秒 paidAt 与纳秒/微秒级 receivedAt。
5. 验证签名仍基于实际传输字段，且正式 BAR 协议没有被测试扩展破坏。
6. 完整重跑 E-PRE，再继续其余波次。

用户当前要求“不着急修改 paid_at”，因此截至本交接文档，该项尚未实现。

## 11. 推荐接手顺序

### 阶段 A：冻结并修复测试器

1. 保留 `membership-millisecond-boundary-20260823-131450` 全部失败证据。
2. 为 JMeter 并发观察异常添加最小失败回归测试。
3. 修复普通 HashSet 的并发移除问题。
4. 为 Runner 添加 JTL 零失败门禁。
5. 为 SQL verifier 的 nullable `closing_deadline_at` 添加最小失败回归测试。
6. 使用计划 `hard_close_at` 修复逐笔裁决 SQL。
7. 按用户要求增加 `paid_at` 毫秒传输与兼容解析。
8. 执行测试器合同测试，并确认源码指纹固定。

### 阶段 B：精确清理失败运行

1. 从 E-PRE `scenario-orders.csv` 读取准确 1,000 个订单 ID。
2. 调用受控 reset 入口删除这 1,000 笔订单、回调和对应支付事实。
3. 把这 1,000 个额度恢复为 FREE；其余 3,000 个不得改坏。
4. 再次确认固定身份、资料、额度各 4,000 条。
5. 确认订单、回调、活动订单、Marker、Redis 异步集合、Rabbit Ready/Unacked/DLQ 均为 0。
6. 删除或隔离过期历史 Token 文件，但不得删除 4,000 个固定用户模板。

### 阶段 C：正式重跑

1. 新 Run ID，2 分钟预检。
2. E-PRE：1,000 笔。
3. E-AFTER：1,000 笔。
4. H-PRE：1,000 笔。
5. H-AFTER：1,000 笔。
6. 每波必须同时满足：JTL 零失败、1,000 订单、1,000 回调、SQL 逐笔裁决 PASS、Redis/RabbitMQ 无异常、源码指纹未变。
7. 全部 4,000 笔完成后再做最终扫描和精确 reset。

如果任何业务代码、SQL、Lua、JMeter 核心断言或回调解析代码发生变化，必须用新源码指纹重新开始受影响波次；不得把不同指纹的结果合并。

## 12. 禁止事项

- 禁止把当前 1,000 笔 PAID/APPLIED 解释为 E-PRE PASS。
- 禁止删除失败运行目录或覆盖 JTL、SQL、CSV、manifest、verdict。
- 禁止直接 UPDATE BAR 数据库状态。
- 禁止使用 8080 应用实例。
- 禁止同时运行两个会员队列消费者实例。
- 禁止使用 Codex 内置浏览器或 Computer Use。
- 禁止删除固定 4,000 个测试身份、资料和额度模板。
- 禁止复用过期 Token 作为新正式运行证据。
- 禁止把不同源码指纹的波次拼接为最终 PASS。

## 13. 关键证据索引

```text
# W01～W08 原运行
loadtest-output/soak/membership-payment-20260823-083156/local

# W08 修正校验器后独立 PASS
loadtest-output/soak/w08-verifier-fix-20260823-1245/local/W08/
  20260823-103622-loadtest-realtime-membership-long-observation

# NOT_GRANTED / REFUND_REQUIRED 20+20 PASS
loadtest-output/soak/entitlement-resolution-matrix-20260823-110115/local/W08S/
  20260823-110115-loadtest-realtime-membership-entitlement-resolution-matrix

# 最新毫秒边界失败运行
loadtest-output/soak/membership-millisecond-boundary-20260823-131450/
  millisecond-boundary

# 第一波详细证据
loadtest-output/soak/membership-millisecond-boundary-20260823-131450/
  millisecond-boundary/E-PRE

# 八组正式输入
loadtest/input/membership-millisecond-boundary-groups.csv

# JMeter 驱动
loadtest/scripts/jmeter/membership-millisecond-boundary.groovy

# 波次 Runner
loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1

# 当前需要修复的 SQL 校验器
loadtest/sql/verify-membership-millisecond-boundary-wave.sql
```
