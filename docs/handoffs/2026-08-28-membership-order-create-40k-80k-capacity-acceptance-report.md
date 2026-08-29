# 会员订单创建 40K/80K 容量测试验收与交接报告

- 报告日期：2026-08-28（America/Chicago）
- 测试对象：会员订单创建、支付回调与毫秒边界裁决全链路
- 测试规模：`8 × 5K = 40K`、`8 × 10K = 80K`
- 报告性质：正式结果验收、问题复盘、续跑与接管手册
- 设计依据：[40K/80K 验收报告设计](../superpowers/specs/2026-08-28-membership-40k-80k-acceptance-report-design.md)

---

## 一、执行摘要

### 1.1 最终结论

本轮最终结论必须分层理解：

| 验收层 | 40K | 80K | 结论 |
| --- | --- | --- | --- |
| 正式区段功能 | 8/8 PASS | 8/8 PASS | PASS |
| HTTP 创建与回调 | 40,000/40,000 | 80,000/80,000 | PASS |
| 性能合同 | 每段 `<5.556s` 且 `>900 QPS` | 每段 `<11.112s` 且 `>900 QPS` | PASS |
| 数据一致性 | 区段级订单/回调相等 | 80,000 订单与 80,000 回调一一对应 | PASS |
| 黄金能力 | 八段均 `REPRODUCED` | 八段均 `GOLDEN_CAPABILITY_TARGET_REACHED` | 达标 |
| 最后续跑 Run 顶层状态 | FAIL | FAIL | 聚合报告假阴性，不是业务失败 |

> **八个业务正式区段与最终数据验收 PASS；顶层聚合报告因四段续跑形态产生假阴性 FAIL。**

两个最后续跑 Run 的顶层原始失败信息相同：

```text
failureCode=MILLISECOND_BOUNDARY_SUITE_FAILURE
originStage=FORMAL_GOLDEN_REPORT
primaryMessage=Order-create HTTP report accepts one canary segment or all eight formal segments.
```

原因是 `New-MembershipOrderCreateHttpReport.ps1` 只接受“单个 Canary 区段”或“同一 Run 中完整八个正式区段”。最后续跑 Run 只包含 `H-P1/H-PR/H-A1/H-AR` 四段，因此区段全部 PASS 后仍在最终聚合阶段抛出异常。这个原始 FAIL 必须保留，但不能把它解释成 Redis、RabbitMQ、PostgreSQL 或业务状态机失败。

### 1.2 扩容结论

订单规模从 40,000 翻倍到 80,000 后：

- 正式墙钟总和从 `30.669231s` 增加到 `59.611025s`，增幅 `94.368%`，低于数据量的 `100%` 增幅。
- 有效 QPS 从 `1,304.239` 增加到 `1,342.034`，提高 `2.898%`。
- 80K 八段最低 QPS 为 `1,136.403`，仍明显高于 `900 QPS` 合同线。
- 80K 八段全部低于 `11.112s` 区段墙钟上限。
- 80K 最终无缺失回调、无重复回调，容量翻倍没有引入数据一致性回退。

因此，80K 容量测试的功能、性能和数据一致性合同均通过。

---

## 二、测试范围与固定合同

### 2.1 八个正式区段

| 顺序 | 区段 | 参照边界 | 调度目标 | 40K 数量 | 80K 数量 |
| ---: | --- | --- | --- | ---: | ---: |
| 1 | E-P1 | `expiresAt` | 全部 `-1ms` | 5,000 | 10,000 |
| 2 | E-PR | `expiresAt` | `-1000ms～-2ms` 循环 | 5,000 | 10,000 |
| 3 | E-A1 | `expiresAt` | 全部 `+1ms` | 5,000 | 10,000 |
| 4 | E-AR | `expiresAt` | `0ms～+998ms` 循环 | 5,000 | 10,000 |
| 5 | H-P1 | `hardCloseAt` | 全部 `-1ms` | 5,000 | 10,000 |
| 6 | H-PR | `hardCloseAt` | `-1000ms～-2ms` 循环 | 5,000 | 10,000 |
| 7 | H-A1 | `hardCloseAt` | 全部 `+1ms` | 5,000 | 10,000 |
| 8 | H-AR | `hardCloseAt` | `0ms～+998ms` 循环 | 5,000 | 10,000 |

`hardCloseAt = expiresAt + 5 minutes`。区段名称表示调度目标，不保证 Windows、JVM、HTTP 或线程调度实现零漂移。最终 `APPLIED/REFUND_REQUIRED` 必须使用服务端实际 `received_at` 与 `hardCloseAt` 比较，不能根据区段名称直接推断。

### 2.2 固定运行合同

| 项目 | 固定值或规则 |
| --- | --- |
| 创建 HTTP 并发 | 256 |
| 回调 HTTP 并发 | 256 |
| PostgreSQL `max_connections` | 384 |
| Hikari `maximumPoolSize/minimumIdle` | 256/8 |
| Redis 写入 | `batchSize=64`、`laneCount=6`、`maximumInflight=384` |
| RabbitMQ PENDING/CLOSING 消费者 | 48/48 |
| RabbitMQ ACK | 手动 ACK，业务成功后确认 |
| 应用实例 | 1，禁止第二个应用重复消费或污染统计 |
| Provider | `LOCAL_SIMULATOR` |
| PostgreSQL | 单主库，`127.0.0.1:5431` |
| 正式区段最低 QPS | 900 |
| 40K 区段最大墙钟 | 5.556s |
| 80K 区段最大墙钟 | 11.112s |

Redis 的 `maximumInflight=384` 是应用层写入 bulkhead，不等于 Lettuce 物理连接池大小。Redis Pipeline 只减少网络 RTT，不提供事务、Exactly Once 或跨 PostgreSQL/RabbitMQ 的原子性。

---

## 三、正式证据拼接关系

由于测试期间在区段之间出现门禁、Token 白名单和最终报告聚合问题，40K 与 80K 都由多个 Run 的“完整正式 PASS 区段”拼接。只拼接已经生成区段级 PASS、scenario CSV 和黄金对照的正式区段，不拼接未完成预热或中断中的数据。

| 规模 | Run ID | 本 Run 已完成正式区段 | 顶层停止点 | 对最终验收的影响 |
| --- | --- | --- | --- | --- |
| 40K | `membership-order-create-auto-20260827-102027-performance-40k` | E-P1、E-PR、E-A1 | E-AR 预热报告要求队列连续三次为零 | 前三段有效并保留 |
| 40K | `membership-order-create-resume-ear-5kx8-20260827-135706` | E-AR | H-P1 预热 PostgreSQL server-time 门禁 | E-AR 有效并保留 |
| 40K | `membership-order-create-resume-hp1-5kx4-20260827-174807` | H-P1、H-PR、H-A1、H-AR | 最终 HTTP 报告器不接受四段续跑 | 后四段有效；顶层 FAIL 为假阴性 |
| 80K | `membership-order-create-capacity-80k-20260828-121713` | E-P1、E-PR、E-A1 | E-A1 后 RabbitMQ 基线非空门禁 | 前三段有效并保留 |
| 80K | `membership-order-create-capacity-80k-20260828-160053` | E-AR | H-P1 Token 第 80 页返回 403 | E-AR 有效并保留 |
| 80K | `membership-order-create-capacity-80k-20260828-164946` | H-P1、H-PR、H-A1、H-AR | 最终 HTTP 报告器不接受四段续跑 | 后四段有效；顶层 FAIL 为假阴性 |

这张表解释了为什么不能只读取最后一个 Run 的顶层状态，也不能把六个历史 Run 的所有失败原因混写成最终业务失败。正确口径是：先验收区段级正式证据，再单独披露编排顶层状态。

---

## 四、40K（8×5K）结果

### 4.1 八段正式结果

| 区段 | 请求/成功 | 回调 | 墙钟(s) | QPS | P50(ms) | P95(ms) | P99(ms) | 合同 | 黄金 | 证据 Run |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| E-P1 | 5,000/5,000 | 5,000 | 3.779350 | 1322.978819 | 131.791 | 224.403 | 279.670 | PASS | REPRODUCED | [`...102027-performance-40k`](../../loadtest-output/soak/membership-order-create-auto-20260827-102027-performance-40k/millisecond-boundary/E-P1/) |
| E-PR | 5,000/5,000 | 5,000 | 3.647276 | 1370.886108 | 130.407 | 238.500 | 296.585 | PASS | REPRODUCED | [`...102027-performance-40k`](../../loadtest-output/soak/membership-order-create-auto-20260827-102027-performance-40k/millisecond-boundary/E-PR/) |
| E-A1 | 5,000/5,000 | 5,000 | 3.585052 | 1394.679910 | 119.588 | 224.758 | 278.686 | PASS | REPRODUCED | [`...102027-performance-40k`](../../loadtest-output/soak/membership-order-create-auto-20260827-102027-performance-40k/millisecond-boundary/E-A1/) |
| E-AR | 5,000/5,000 | 5,000 | 4.347590 | 1150.062448 | 111.805 | 204.767 | 250.119 | PASS | REPRODUCED | [`...135706`](../../loadtest-output/soak/membership-order-create-resume-ear-5kx8-20260827-135706/millisecond-boundary/E-AR/) |
| H-P1 | 5,000/5,000 | 5,000 | 4.797223 | 1042.269663 | 169.191 | 301.284 | 366.253 | PASS | REPRODUCED | [`...174807`](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/H-P1/) |
| H-PR | 5,000/5,000 | 5,000 | 3.487575 | 1433.660925 | 111.099 | 193.633 | 224.673 | PASS | REPRODUCED | [`...174807`](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/H-PR/) |
| H-A1 | 5,000/5,000 | 5,000 | 3.358059 | 1488.955376 | 112.777 | 189.092 | 219.065 | PASS | REPRODUCED | [`...174807`](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/H-A1/) |
| H-AR | 5,000/5,000 | 5,000 | 3.667106 | 1363.472995 | 118.176 | 212.885 | 253.392 | PASS | REPRODUCED | [`...174807`](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/H-AR/) |

### 4.2 40K 汇总

| 指标 | 结果 |
| --- | ---: |
| 请求总数 | 40,000 |
| 成功总数 | 40,000 |
| 回调总数 | 40,000 |
| 正式墙钟总和 | 30.669231s |
| 有效 QPS | 1,304.239 |
| 平均区段 QPS | 1,320.871 |
| 最低区段 QPS | 1,042.270（H-P1） |
| 最高区段 QPS | 1,488.955（H-A1） |

H-P1 是 40K 中最慢区段，同时具有最高的 P50/P95/P99；但 `4.797223s` 仍低于 5.556s，`1042.269663 QPS` 仍高于 900 QPS，而且满足 `<5s` 与 `>1000 QPS` 的黄金能力条件。八段全部复现黄金能力。

---

## 五、80K（8×10K）结果

### 5.1 八段正式结果

| 区段 | 请求/成功 | 回调 | 墙钟(s) | QPS | P50(ms) | P95(ms) | P99(ms) | 合同 | 黄金 | 证据 Run |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| E-P1 | 10,000/10,000 | 10,000 | 7.920097 | 1262.610799 | 123.624 | 238.406 | 296.573 | PASS | TARGET_REACHED | [`...121713`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-121713/millisecond-boundary/E-P1/) |
| E-PR | 10,000/10,000 | 10,000 | 6.727252 | 1486.491066 | 103.445 | 192.639 | 257.802 | PASS | TARGET_REACHED | [`...121713`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-121713/millisecond-boundary/E-PR/) |
| E-A1 | 10,000/10,000 | 10,000 | 6.826075 | 1464.970719 | 89.406 | 161.611 | 192.353 | PASS | TARGET_REACHED | [`...121713`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-121713/millisecond-boundary/E-A1/) |
| E-AR | 10,000/10,000 | 10,000 | 6.809635 | 1468.507490 | 100.177 | 177.176 | 228.354 | PASS | TARGET_REACHED | [`...160053`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-160053/millisecond-boundary/E-AR/) |
| H-P1 | 10,000/10,000 | 10,000 | 7.937467 | 1259.847757 | 133.273 | 315.231 | 390.448 | PASS | TARGET_REACHED | [`...164946`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/H-P1/) |
| H-PR | 10,000/10,000 | 10,000 | 7.600742 | 1315.661024 | 129.760 | 300.780 | 401.918 | PASS | TARGET_REACHED | [`...164946`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/H-PR/) |
| H-A1 | 10,000/10,000 | 10,000 | 8.799693 | 1136.403281 | 123.619 | 227.155 | 278.491 | PASS | TARGET_REACHED | [`...164946`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/H-A1/) |
| H-AR | 10,000/10,000 | 10,000 | 6.990064 | 1430.602066 | 107.266 | 209.895 | 340.256 | PASS | TARGET_REACHED | [`...164946`](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/H-AR/) |

`TARGET_REACHED` 对应原始 JSON 的 `GOLDEN_CAPABILITY_TARGET_REACHED`。

### 5.2 80K 汇总

| 指标 | 结果 |
| --- | ---: |
| 请求总数 | 80,000 |
| 成功总数 | 80,000 |
| 回调总数 | 80,000 |
| 正式墙钟总和 | 59.611025s |
| 有效 QPS | 1,342.034 |
| 平均区段 QPS | 1,353.137 |
| 最低区段 QPS | 1,136.403（H-A1） |
| 最高区段 QPS | 1,486.491（E-PR） |

80K 最慢区段为 H-A1，墙钟 `8.799693s`，仍比 11.112s 上限低 2.312307s。尾延迟最高值出现在 H-PR 的 P99 `401.918ms`，但没有导致 HTTP 失败、合同 QPS 下降到阈值以下或数据库事实缺失。

80K 每段是 10,000 条，不能用 5K 黄金样本的 `<5s` 绝对墙钟直接否定容量结果。正确判定是：墙钟低于 11.112s、QPS 高于 900、HTTP 全成功、数据完整，且报告器给出黄金能力目标达到。

---

## 六、40K/80K 横向比较

| 指标 | 40K | 80K | 变化 |
| --- | ---: | ---: | ---: |
| 订单/回调 | 40,000/40,000 | 80,000/80,000 | +100% |
| 正式墙钟总和 | 30.669231s | 59.611025s | +94.368% |
| 有效 QPS | 1,304.239 | 1,342.034 | +2.898% |
| 平均区段 QPS | 1,320.871 | 1,353.137 | +2.443% |
| 最低区段 QPS | 1,042.270 | 1,136.403 | +9.032% |
| 最高区段 QPS | 1,488.955 | 1,486.491 | -0.166% |
| 区段 PASS | 8/8 | 8/8 | 持续达标 |

扩容后的吞吐没有按数据量翻倍而衰减。墙钟近似线性增长，但增长率略低于 100%；有效 QPS 和最低区段 QPS 均提高。H 系列的 P95/P99 仍高于大多数 E 系列，说明硬关闭边界链路的尾延迟仍是后续优化重点，但当前没有越过功能或性能合同。

---

## 七、最终一致性验收

### 7.1 PostgreSQL

80K 完成后的人工 SQL 核验结果：

| 项目 | 数量 | 结果 |
| --- | ---: | --- |
| `membership_order` | 80,000 | PASS |
| `membership_payment_callback` | 80,000 | PASS |
| callback 不同 `order_id` | 80,000 | PASS |
| 缺失 callback 的订单 | 0 | PASS |
| 同订单重复 callback | 0 | PASS |
| 订单 `APPLIED` | 49,963 | 与 callback 一致 |
| 订单 `REFUND_REQUIRED` | 30,037 | 与 callback 一致 |
| callback `APPLIED` | 49,963 | 与订单一致 |
| callback `REFUND_REQUIRED` | 30,037 | 与订单一致 |

复核 SQL 可以使用：

```sql
SELECT COUNT(*) AS orders FROM membership_order;
SELECT COUNT(*) AS callbacks, COUNT(DISTINCT order_id) AS distinct_callback_orders FROM membership_payment_callback;
SELECT COUNT(*) AS missing_callbacks FROM membership_order o LEFT JOIN membership_payment_callback c ON c.order_id=o.id WHERE c.id IS NULL;
SELECT COUNT(*) AS duplicate_callback_orders FROM (SELECT order_id FROM membership_payment_callback GROUP BY order_id HAVING COUNT(*)>1) d;
SELECT entitlement_resolution, COUNT(*) FROM membership_order GROUP BY entitlement_resolution ORDER BY entitlement_resolution;
SELECT resolution, COUNT(*) FROM membership_payment_callback GROUP BY resolution ORDER BY resolution;
```

本报告没有在编写阶段重新连接数据库；上表记录的是测试完成后的既有人工核验事实。

### 7.2 Redis

- Redis 快照和工作集合用于承载实时赢家、dirty 队列、callback 工作及本机 Provider 结果。
- Pipeline 优化减少 RTT 和队头阻塞，但不改变单订单 Lua 的独立原子边界。
- 最终验收依赖 Redis 工作集合收敛、RabbitMQ 队列收敛与 PostgreSQL 最终事实三者共同成立。
- 不宣称 Redis Pipeline 是事务、Exactly Once 或跨 PostgreSQL/RabbitMQ 强一致。

### 7.3 RabbitMQ

- 正常运行时 PENDING 与 CLOSING 队列各有 48 个消费者。
- 消费者属于应用进程；应用停止后消费者会消失，RabbitMQ Broker 本身仍可能正常运行。
- 到期消息没有消费者时会停留在 Ready；应用恢复并重新注册消费者后才能继续收敛。
- `Ready=0/Unacked=0` 只能证明当时队列没有积压，不能单独证明数据库与业务结果正确。
- 本轮最终结论同时使用了区段级回调、最终数据库和队列证据。

### 7.4 日志与 Navicat

正式日志配置会全量保留 `ORDER_CREATE`、`PAYMENT_ATTEMPT`，其他操作只记录慢事件、失败、NACK 或重投。快速正常的 PENDING/CLOSING 没有绿字，不代表消费者没有执行。

Navicat 曾显示 callback 只有 50,000 条，是因为查询底部存在：

```sql
LIMIT 50000 OFFSET 0
```

这只是客户端分页/查询上限，不是 callback 丢失。实际总数必须用 `COUNT(*)` 查询。

---

## 八、测试过程中发现的问题与处置

### 8.1 取消与支付发起竞态

- **现象：** 取消已在 Redis 成为 `CANCELLED`，支付发起仍可能依据 PostgreSQL 旧 `PENDING_PAYMENT` 成功提交，形成相同 `stateVersion`、不同状态的分叉。
- **根因：** PostgreSQL 条件更新前没有拒绝 Redis 已存在的终态；提交后也没有重新读取可能由并发回调或取消推进的 Redis 胜出快照。
- **修复：** 支付发起流程增加提交前实时终态门禁，并在 PostgreSQL 提交、Redis 刷新后再次读取赢家，拒绝并发产生的 `PAID/CANCELLED/CLOSED`。
- **验证：** X-01 专项订单最终保持 `CANCELLED`，callback 为 `REFUND_REQUIRED`，不会被支付发起复活。
- **最终影响：** 修复后正式 40K/80K 不存在该类分叉。
- **遗留风险：** 项目不使用分布式事务，最终幂等仍依赖 PostgreSQL 唯一约束、单调版本和有界恢复。

### 8.2 `REJECTED` 后永久停在 `CLOSING`

- **现象：** 非法成功回调被正确裁决为 `REJECTED`，但订单在硬截止后长期停留 `CLOSING`。
- **根因：** 旧 callback complete 删除了模拟 Provider 结果；后续关单查询只能得到 `UNKNOWN`，有限重试耗尽后消息进入 DLQ。系统为避免把未知支付误关单，只能保留 CLOSING。
- **修复：** `REJECTED` 完成动作改为原子 `RESET_UNPAID`，仅在 provider-result 仍属于当前 callback 时清空流水字段并保留明确 `UNPAID` 事实。
- **验证：** 专项探针在约 10 分钟真实 5+5 时间链后从 `REJECTED` 收敛到 `CLOSED`。
- **最终影响：** 新产生订单可以安全收敛；历史故障订单不冒充已自动恢复。
- **遗留风险：** 外部 Provider 真正返回 UNKNOWN 时仍必须有限重试和人工诊断，不能直接当 UNPAID。

### 8.3 JMeter `paidAt` 时间精度

- **现象：** 计划为合法成功的场景偶尔被裁决 `REJECTED`。
- **根因：** JMeter 早期把 `paidAt` 构造成整秒，而服务端 `paymentStartedAt` 带毫秒/微秒，可能出现 `paidAt < paymentStartedAt`。
- **修复：** 合法成功场景按服务端精度构造时间，确保 `paidAt >= paymentStartedAt`。
- **验证：** 修正后边界区段按服务端 `received_at` 正常裁决。
- **最终影响：** 属于测试数据缺陷，不是产品状态机误判。
- **遗留风险：** 后续扩展 JMeter 场景必须继续使用微秒语义，不能退回整秒。

### 8.4 callback 订单级和流水级幂等

- **现象：** 只按第三方流水唯一时，同一订单可使用不同 `provider_trade_no` 写入多条 callback。
- **根因：** 缺少订单级唯一性，Redis 快速幂等也不能替代持久化最终约束。
- **修复：** 同时约束 `order_id` 与 `provider_trade_no`；合法重复通知返回成功但不重新入队、不新增 callback、不覆盖原事实。
- **验证：** 80K 最终 callback 总数与不同 callback order 数均为 80,000，重复订单数为 0。
- **最终影响：** PASS。
- **遗留风险：** Redis 幂等 Key 过期后仍必须由 PostgreSQL 唯一约束兜底。

### 8.5 Redis 重 Pipeline 尾延迟

- **现象：** 旧 `192×2/384` 在 384 个逻辑写入下形成较重 Pipeline，Redis 协调器排队和 Pipeline 尾延迟扩大完整 HTTP 墙钟。
- **根因：** 单 lane 批次大、lane 数少，独立单订单 Lua 在同一 Pipeline 中产生队头阻塞；数据库事务不是主要瓶颈。
- **修复：** 调整为 `64×6/384`：总在途仍为 384，同一订单稳定散列到同一 lane，每 lane FIFO、一次最多一个 64 条 Pipeline。
- **验证：** 40K 有效 QPS 1,304.239；80K 有效 QPS 1,342.034，全部区段超过 900。
- **最终影响：** 容量合同 PASS。
- **遗留风险：** 六 lane 增加线程切换和 Pipeline 交错，仍需监控队列偏斜、Pipeline P95/P99 和超时。

设计依据：[Redis 64×6/384 ADR](../architecture/adr-2026-08-26-membership-payment-redis-write-64x6.md)。

### 8.6 Callback 与 OrderPersist 调度容量

- **现象：** 共用默认调度器及旧单轮 `20×100=2,000` 无法在集中 5,000 条 backlog 下稳定收敛。
- **根因：** 两类任务相互阻塞，单轮容量小于正式区段集中写入量。
- **修复：** Callback 与 OrderPersist 使用独立单线程调度器，单轮提升为 `50×100=5,000`。
- **验证：** 正式区段回调数与订单数相等，最终 dirty/callback 工作集合收敛。
- **最终影响：** PASS。
- **遗留风险：** 单轮 5,000 是有界容量，不应扩展成无界循环。

### 8.7 最近已支付查询与部分索引

- **现象：** 最近 PAID 查询在大表或 JDBC 泛化计划下可能无法使用部分索引。
- **根因：** 把 PAID 状态作为 JDBC 参数时，PostgreSQL 无法总是证明查询谓词蕴含索引的 `WHERE status=2`。
- **修复：** SQL 固定 `status=2`，增加 `idx_membership_order_latest_paid`，排序覆盖 `paid_at/created_at/id DESC`。
- **验证：** 使用真实 EXPLAIN 与隔离 10,000 条历史订单测试验证 Index Scan/Index Only Scan，无独立 Sort。
- **最终影响：** 没有给 40K/80K 创建链路引入回退。
- **遗留风险：** 仅看索引存在不等于命中，后续必须继续记录 `idx_scan` 前后差值。

### 8.8 连接池与 RabbitMQ Channel 容量

- **现象：** HTTP 并发、Hikari、PostgreSQL、Rabbit Publisher Channel 和固定消费者如果各自独立放大，会互相争抢资源。
- **根因：** 把应用逻辑并发误等同于数据库连接或 Rabbit 消费者数量。
- **修复：** PostgreSQL 384、Hikari 256/8、Redis inflight 384、HTTP 256，Rabbit 两类消费者保持 48，并配置足够的客户端 channel max。
- **验证：** 80K 八段无 HTTP 创建失败，最低 QPS 仍为 1,136.403。
- **最终影响：** PASS。
- **遗留风险：** 多应用实例会改变总连接与消费者数量，本报告只适用于单实例合同。

### 8.9 合法延迟消息被旧门禁误判

- **现象：** 正式区段已完成，但 RabbitMQ/Redis 中仍有处于合法 TTL、延迟投递或边界状态竞争窗口的消息，旧门禁立即要求全零并判 FAIL。
- **根因：** 门禁没有区分“当前 Run 的合法延迟消息”和“上一轮不可收敛残留”，也没有给有界收敛窗口。
- **修复：** 门禁按 Run/场景证据识别消息，在有界窗口中观察稳定收敛；只有超出合法时间、持续不下降或归属不明才失败。
- **验证：** 后续正式区段能够在不清空业务队列的情况下完成，并由最终数据库事实确认没有消息丢失。
- **最终影响：** 旧 FAIL 属于门禁假阳性，不否定已完整通过的区段。
- **遗留风险：** 不能简单延长等待掩盖真实积压，必须同时观察 Ready、Unacked、DLQ 和数据库终态。

### 8.10 应用停止后 RabbitMQ 消费者消失

- **现象：** 订单已经到期但仍为 `status=0`，回调无新增，旧心跳停止更新。
- **根因：** 测试应用进程被停止，PENDING/CLOSING 消费者随应用退出；RabbitMQ Broker 没有崩溃，但到期消息没有消费者处理。
- **处置：** 启动唯一恢复应用后，支付与关闭队列均重新出现 48 个消费者，Ready/Unacked 归零，数据库从 pending 收敛到 `paid=30,000/closed=10,000` 的当时 40K 状态。
- **验证：** 恢复监控显示 Application RUNNING、消费者 48/48、队列 0/0、`expiredPending=0`。
- **最终影响：** 这是应用生命周期问题，不是 RabbitMQ 内部崩溃。
- **遗留风险：** 恢复应用只恢复消费能力，不会复活已经死亡的旧调度器或旧心跳。

### 8.11 外部强制结束绕过 `STOPPED` 收尾

- **现象：** `run-state.json` 仍显示 RUNNING，`heartbeat.json` 的 `sampledAt` 永久停在旧时间；正常历史 Run 则会写入 `phase=STOPPED`。
- **根因：** 调度器进程被外部强制结束，PowerShell `finally`/正常收尾没有执行。
- **处置：** 接管时以 PID、命令行、stdout/stderr 和心跳年龄联合判断，不能只相信旧 JSON。
- **验证：** 旧 Application PID 已为 STOPPED，但新恢复应用消费者正常；两者属于不同进程和不同 Run。
- **最终影响：** 不影响已落盘正式区段，但使原 Run 无法继续更新心跳。
- **遗留风险：** 关闭终端、宿主 Job Object 或强杀进程树都可能产生相同行为。

### 8.12 80K Token 页码仍限制为 40K

- **现象：** `E-AR` 完成后，H-P1 第一遍预热调用 Token 第 80 页返回 403，并因 scenario CSV 尚未生成而连带出现精确清理错误。
- **根因：** 每页 500 个用户。40K 只需要 0～79 页，80K 需要 0～159 页；请求策略仍只允许 0～79。
- **修复：** 白名单改为仅允许无前导零的 0～159；允许 80、159，拒绝 160、080。重新打包并将三个启动脚本锁定到新 JAR SHA-256：`b3c924c4abf49266957b9f93076fa2268e5c1e7e447899a1411eff16375ac597`。
- **验证：** 定向策略测试 10/10 通过；新 JAR 完成 H-P1～H-AR 四段，每段 10,000/10,000 PASS。
- **最终影响：** 属于 80K 测试开关/请求白名单配置缺陷，不是 RabbitMQ 或 Redis 故障。
- **遗留风险：** 再次扩大容量时必须同步计算页数上限，不能只修改 CSV 规模。

### 8.13 四段续跑触发顶层聚合假阴性

- **现象：** H-P1～H-AR 四段全部 PASS，随后顶层 verdict 仍为 FAIL。
- **根因：** 报告器硬编码只接受一段 Canary 或八段正式结果，不接受合法四段续跑。
- **处置：** 本报告按 authoritative scenario 清单拼接八段并保留顶层原始 FAIL；不篡改历史 verdict。
- **验证：** 两个最后 Run 的 `originStage` 都是 `FORMAL_GOLDEN_REPORT`，错误消息完全一致；四个区段各自 verdict 与 golden comparison 全部 PASS。
- **最终影响：** 顶层聚合假阴性，不影响区段与最终数据验收。
- **遗留风险：** 报告器未支持任意合法续跑段数前，续跑仍可能产生同类顶层 FAIL。

### 8.14 Navicat 50,000 条显示上限

- **现象：** 订单表有 80,000 条，但 callback 结果页只看到 50,000 条。
- **根因：** 查询带 `LIMIT 50000 OFFSET 0`。
- **处置：** 使用 `COUNT(*)` 与 `COUNT(DISTINCT order_id)`，不要用结果网格显示行数代替总数。
- **验证：** callback 总数与不同订单数均为 80,000。
- **最终影响：** 客户端查询方式问题，没有数据丢失。
- **遗留风险：** 大表 GUI 默认分页仍可能造成相同误读。

### 8.15 RabbitMQ 查询与清理不能混用

- **现象：** `list_queues` 被误解为会清理消息，或把 DLQ 中 10 条测试残留当作必须每轮手工 purge。
- **根因：** 查询命令与 `purge_queue` 的破坏性语义混淆。
- **处置：** `list_queues` 只读；只有明确证明精确队列内全部消息均为可删除测试残留时，才单独授权 `purge_queue`。
- **验证：** 查询命令只返回 name、consumers、Ready、Unacked，不修改 Broker。
- **最终影响：** 标准测试流程不需要手工 purge。
- **遗留风险：** `purge_queue` 删除目标队列全部 Ready 消息且不可恢复，禁止清理 `membership.*` 全部队列或正常业务队列。

### 8.16 关于 etcd

本轮六个正式 Run、现有故障文档和运行证据均没有 etcd 参与链路或 etcd 故障证据。报告不把语音识别中的“etcd”描述为真实根因；能被证据支持的相关基础设施只有 PostgreSQL、Redis 和 RabbitMQ。

---

## 九、启动、续跑与监控命令

> 以下命令用于本地隔离压测环境。报告编写阶段只做 PowerShell 静态语法解析，没有执行这些命令。启动前必须确认没有冲突的应用、JMeter 或调度器进程。

### 9.1 从 E-P1 启动完整八段

这条命令引用当前已经完成的 80K 八段 scenario 文件作为精确清理证据，创建全新的 Master/Run ID，执行 120 秒 PostgreSQL 门禁并从 E-P1 跑完整八段。

```powershell
$projectRoot='C:\Users\damn\Desktop\ai-temperate-main';function Read-J($p){$f=[IO.File]::Open($p,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete));try{$r=[IO.StreamReader]::new($f);try{$r.ReadToEnd()|ConvertFrom-Json}finally{$r.Dispose()}}finally{$f.Dispose()}};$masterRunId='membership-order-create-capacity-80k-20260828-121713';$eArRunId='membership-order-create-capacity-80k-20260828-160053';$hRunId='membership-order-create-capacity-80k-20260828-164946';$masterRoot=Join-Path $projectRoot "loadtest-output\soak\$masterRunId\millisecond-boundary";$eArRoot=Join-Path $projectRoot "loadtest-output\soak\$eArRunId\millisecond-boundary";$hRoot=Join-Path $projectRoot "loadtest-output\soak\$hRunId\millisecond-boundary";$previousScenarios=@((Join-Path $masterRoot 'E-P1\scenario-orders.csv'),(Join-Path $masterRoot 'E-PR\scenario-orders.csv'),(Join-Path $masterRoot 'E-A1\scenario-orders.csv'),(Join-Path $eArRoot 'E-AR\scenario-orders.csv'),(Join-Path $hRoot 'H-P1\scenario-orders.csv'),(Join-Path $hRoot 'H-PR\scenario-orders.csv'),(Join-Path $hRoot 'H-A1\scenario-orders.csv'),(Join-Path $hRoot 'H-AR\scenario-orders.csv'));foreach($required in $previousScenarios){if(-not(Test-Path -LiteralPath $required -PathType Leaf)){throw "缺少上一轮清理证据：$required"}};$runId='membership-order-create-capacity-80k-'+(Get-Date -Format 'yyyyMMdd-HHmmss');$logRoot=Join-Path $projectRoot 'loadtest-output\launcher';New-Item -ItemType Directory -Force -Path $logRoot|Out-Null;$runIdFile=Join-Path $logRoot 'current-80k-run-id.txt';$archiveRoot=Join-Path $logRoot "prelaunch-log-archive\$runId";foreach($formalLog in @((Join-Path $projectRoot 'logs\membership-payment-state-machine.log'),(Join-Path $projectRoot 'logs\membership-order-create-http-events.log'))){if((Test-Path -LiteralPath $formalLog -PathType Leaf)-and(Get-Item -LiteralPath $formalLog).Length -gt 0){New-Item -ItemType Directory -Force -Path $archiveRoot|Out-Null;$destination=Join-Path $archiveRoot ([IO.Path]::GetFileName($formalLog));if(Test-Path -LiteralPath $destination){throw "日志归档目标已存在：$destination"};Move-Item -LiteralPath $formalLog -Destination $destination}};$scheduler=Join-Path $projectRoot 'loadtest\scripts\Start-MembershipSchedulerIndexHikariRetest.ps1';$scenarioLiteral=($previousScenarios|ForEach-Object{"'"+$_.Replace("'","''")+"'"})-join ',';$runCommand="& '$scheduler' -RunId '$runId' -MasterRunId '$runId' -RunScale CAPACITY_80K -StartGroupCode E-P1 -ExpectedFormalSegmentCount 8 -PreviousScenarioListIsAuthoritative -PreviousScenarioOrdersCsvPath @($scenarioLiteral) -PostgresStabilitySeconds 120 -PostgresMaxConnections 384 -HikariMaximumPoolSize 256 -HikariMinimumIdle 8 -MaximumNavicatConnections 8 -RedisWriteBatchSize 64 -RedisWriteLaneCount 6 -RedisWriteMaximumInflight 384";$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($runCommand));$stdoutLog=Join-Path $logRoot "$runId.stdout.log";$stderrLog=Join-Path $logRoot "$runId.stderr.log";$p=Start-Process pwsh -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded -WorkingDirectory $projectRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -WindowStyle Hidden -PassThru;[IO.File]::WriteAllText($runIdFile,$runId,[Text.UTF8Encoding]::new($false));$statePath=Join-Path $projectRoot "loadtest-output\soak\$runId\millisecond-boundary\run-state.json";$deadline=(Get-Date).AddSeconds(180);$ready=$false;$s=$null;while((Get-Date)-lt$deadline){$p.Refresh();if(Test-Path -LiteralPath $statePath -PathType Leaf){try{$s=Read-J $statePath;if(($s.status -eq 'RUNNING')-and($null-ne$s.applicationPid)){$ready=$true;break};if($s.status -in @('FAIL','TEST_INVALID')){break}}catch{}};if($p.HasExited){break};Start-Sleep -Seconds 1};if(-not$ready){$errorText=if(Test-Path -LiteralPath $stderrLog){Get-Content -LiteralPath $stderrLog -Raw}else{'未生成错误日志'};throw "调度器未成功启动：RunId=$runId；$errorText"};"RunId=$runId";"PID=$($p.Id)";"ApplicationPID=$($s.applicationPid)";"Status=$($s.status)";"Evidence=$projectRoot\loadtest-output\soak\$runId\millisecond-boundary"
```

### 9.2 从 E-AR 续跑剩余五段

只在 E-P1、E-PR、E-A1 已完整 PASS，且三个 scenario CSV 和原 PostgreSQL 门禁证据均存在时使用。

```powershell
$projectRoot='C:\Users\damn\Desktop\ai-temperate-main';function Read-J($p){$f=[IO.File]::Open($p,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete));try{$r=[IO.StreamReader]::new($f);try{$r.ReadToEnd()|ConvertFrom-Json}finally{$r.Dispose()}}finally{$f.Dispose()}};$completedRunId='membership-order-create-capacity-80k-20260828-121713';$previousRoot=Join-Path $projectRoot "loadtest-output\soak\$completedRunId\millisecond-boundary";$previousScenarios=@((Join-Path $previousRoot 'E-P1\scenario-orders.csv'),(Join-Path $previousRoot 'E-PR\scenario-orders.csv'),(Join-Path $previousRoot 'E-A1\scenario-orders.csv'));$postgresGate=Join-Path $previousRoot 'postgres-stability-gate.json';foreach($required in $previousScenarios+@($postgresGate)){if(-not(Test-Path -LiteralPath $required -PathType Leaf)){throw "缺少续跑证据：$required"}};$runId='membership-order-create-capacity-80k-'+(Get-Date -Format 'yyyyMMdd-HHmmss');$logRoot=Join-Path $projectRoot 'loadtest-output\launcher';New-Item -ItemType Directory -Force -Path $logRoot|Out-Null;$runIdFile=Join-Path $logRoot 'current-80k-run-id.txt';$archiveRoot=Join-Path $logRoot "prelaunch-log-archive\$runId";foreach($formalLog in @((Join-Path $projectRoot 'logs\membership-payment-state-machine.log'),(Join-Path $projectRoot 'logs\membership-order-create-http-events.log'))){if((Test-Path -LiteralPath $formalLog -PathType Leaf)-and(Get-Item -LiteralPath $formalLog).Length -gt 0){New-Item -ItemType Directory -Force -Path $archiveRoot|Out-Null;$destination=Join-Path $archiveRoot ([IO.Path]::GetFileName($formalLog));if(Test-Path -LiteralPath $destination){throw "日志归档目标已存在：$destination"};Move-Item -LiteralPath $formalLog -Destination $destination}};$scheduler=Join-Path $projectRoot 'loadtest\scripts\Start-MembershipSchedulerIndexHikariRetest.ps1';$scenarioLiteral=($previousScenarios|ForEach-Object{"'"+$_.Replace("'","''")+"'"})-join ',';$runCommand="& '$scheduler' -RunId '$runId' -MasterRunId '$completedRunId' -RunScale CAPACITY_80K -StartGroupCode E-AR -ExpectedFormalSegmentCount 0 -SkipInitialGates -PreviousScenarioListIsAuthoritative -PreviousScenarioOrdersCsvPath @($scenarioLiteral) -ExistingPostgresStabilityGatePath '$postgresGate' -PostgresStabilitySeconds 0 -PostgresMaxConnections 384 -HikariMaximumPoolSize 256 -HikariMinimumIdle 8 -MaximumNavicatConnections 8 -RedisWriteBatchSize 64 -RedisWriteLaneCount 6 -RedisWriteMaximumInflight 384";$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($runCommand));$stdoutLog=Join-Path $logRoot "$runId.stdout.log";$stderrLog=Join-Path $logRoot "$runId.stderr.log";$p=Start-Process pwsh -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded -WorkingDirectory $projectRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -WindowStyle Hidden -PassThru;[IO.File]::WriteAllText($runIdFile,$runId,[Text.UTF8Encoding]::new($false));$statePath=Join-Path $projectRoot "loadtest-output\soak\$runId\millisecond-boundary\run-state.json";$deadline=(Get-Date).AddSeconds(180);$ready=$false;$s=$null;while((Get-Date)-lt$deadline){$p.Refresh();if(Test-Path -LiteralPath $statePath -PathType Leaf){try{$s=Read-J $statePath;if(($s.status -eq 'RUNNING')-and($null-ne$s.applicationPid)){$ready=$true;break};if($s.status -in @('FAIL','TEST_INVALID')){break}}catch{}};if($p.HasExited){break};Start-Sleep -Seconds 1};if(-not$ready){$errorText=if(Test-Path -LiteralPath $stderrLog){Get-Content -LiteralPath $stderrLog -Raw}else{'未生成错误日志'};throw "调度器未成功启动：RunId=$runId；$errorText"};"RunId=$runId";"PID=$($p.Id)";"ApplicationPID=$($s.applicationPid)";"Status=$($s.status)";"Evidence=$projectRoot\loadtest-output\soak\$runId\millisecond-boundary"
```

### 9.3 从 H-P1 续跑剩余四段

只在四个 E 区段均完整 PASS 时使用。前三段来自 Master Run，E-AR 来自单独续跑 Run。

```powershell
$projectRoot='C:\Users\damn\Desktop\ai-temperate-main';function Read-J($p){$f=[IO.File]::Open($p,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete));try{$r=[IO.StreamReader]::new($f);try{$r.ReadToEnd()|ConvertFrom-Json}finally{$r.Dispose()}}finally{$f.Dispose()}};$masterRunId='membership-order-create-capacity-80k-20260828-121713';$eArRunId='membership-order-create-capacity-80k-20260828-160053';$masterRoot=Join-Path $projectRoot "loadtest-output\soak\$masterRunId\millisecond-boundary";$eArRoot=Join-Path $projectRoot "loadtest-output\soak\$eArRunId\millisecond-boundary";$previousScenarios=@((Join-Path $masterRoot 'E-P1\scenario-orders.csv'),(Join-Path $masterRoot 'E-PR\scenario-orders.csv'),(Join-Path $masterRoot 'E-A1\scenario-orders.csv'),(Join-Path $eArRoot 'E-AR\scenario-orders.csv'));$postgresGate=Join-Path $masterRoot 'postgres-stability-gate.json';foreach($required in $previousScenarios+@($postgresGate)){if(-not(Test-Path -LiteralPath $required -PathType Leaf)){throw "缺少续跑证据：$required"}};$runId='membership-order-create-capacity-80k-'+(Get-Date -Format 'yyyyMMdd-HHmmss');$logRoot=Join-Path $projectRoot 'loadtest-output\launcher';New-Item -ItemType Directory -Force -Path $logRoot|Out-Null;$runIdFile=Join-Path $logRoot 'current-80k-run-id.txt';$archiveRoot=Join-Path $logRoot "prelaunch-log-archive\$runId";foreach($formalLog in @((Join-Path $projectRoot 'logs\membership-payment-state-machine.log'),(Join-Path $projectRoot 'logs\membership-order-create-http-events.log'))){if((Test-Path -LiteralPath $formalLog -PathType Leaf)-and(Get-Item -LiteralPath $formalLog).Length -gt 0){New-Item -ItemType Directory -Force -Path $archiveRoot|Out-Null;$destination=Join-Path $archiveRoot ([IO.Path]::GetFileName($formalLog));if(Test-Path -LiteralPath $destination){throw "日志归档目标已存在：$destination"};Move-Item -LiteralPath $formalLog -Destination $destination}};$scheduler=Join-Path $projectRoot 'loadtest\scripts\Start-MembershipSchedulerIndexHikariRetest.ps1';$scenarioLiteral=($previousScenarios|ForEach-Object{"'"+$_.Replace("'","''")+"'"})-join ',';$runCommand="& '$scheduler' -RunId '$runId' -MasterRunId '$masterRunId' -RunScale CAPACITY_80K -StartGroupCode H-P1 -ExpectedFormalSegmentCount 0 -SkipInitialGates -PreviousScenarioListIsAuthoritative -PreviousScenarioOrdersCsvPath @($scenarioLiteral) -ExistingPostgresStabilityGatePath '$postgresGate' -PostgresStabilitySeconds 0 -PostgresMaxConnections 384 -HikariMaximumPoolSize 256 -HikariMinimumIdle 8 -MaximumNavicatConnections 8 -RedisWriteBatchSize 64 -RedisWriteLaneCount 6 -RedisWriteMaximumInflight 384";$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($runCommand));$stdoutLog=Join-Path $logRoot "$runId.stdout.log";$stderrLog=Join-Path $logRoot "$runId.stderr.log";$p=Start-Process pwsh -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded -WorkingDirectory $projectRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -WindowStyle Hidden -PassThru;[IO.File]::WriteAllText($runIdFile,$runId,[Text.UTF8Encoding]::new($false));$statePath=Join-Path $projectRoot "loadtest-output\soak\$runId\millisecond-boundary\run-state.json";$deadline=(Get-Date).AddSeconds(180);$ready=$false;$s=$null;while((Get-Date)-lt$deadline){$p.Refresh();if(Test-Path -LiteralPath $statePath -PathType Leaf){try{$s=Read-J $statePath;if(($s.status -eq 'RUNNING')-and($null-ne$s.applicationPid)){$ready=$true;break};if($s.status -in @('FAIL','TEST_INVALID')){break}}catch{}};if($p.HasExited){break};Start-Sleep -Seconds 1};if(-not$ready){$errorText=if(Test-Path -LiteralPath $stderrLog){Get-Content -LiteralPath $stderrLog -Raw}else{'未生成错误日志'};throw "调度器未成功启动：RunId=$runId；$errorText"};"RunId=$runId";"PID=$($p.Id)";"ApplicationPID=$($s.applicationPid)";"Status=$($s.status)";"Evidence=$projectRoot\loadtest-output\soak\$runId\millisecond-boundary"
```

### 9.4 每两秒监听当前 Run 心跳

```powershell
$projectRoot='C:\Users\damn\Desktop\ai-temperate-main';function Read-J($p){$f=[IO.File]::Open($p,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete));try{$r=[IO.StreamReader]::new($f);try{$r.ReadToEnd()|ConvertFrom-Json}finally{$r.Dispose()}}finally{$f.Dispose()}};$runIdFile=Join-Path $projectRoot 'loadtest-output\launcher\current-80k-run-id.txt';if(-not(Test-Path -LiteralPath $runIdFile -PathType Leaf)){throw "缺少当前80K RunId文件：$runIdFile"};$runId=(Get-Content -LiteralPath $runIdFile -Raw).Trim();$count=0;$started=Get-Date;while($true){$count++;Clear-Host;try{$d=Get-Item -LiteralPath (Join-Path $projectRoot "loadtest-output\soak\$runId") -ErrorAction Stop;$root=Join-Path $d.FullName 'millisecond-boundary';$s=Read-J (Join-Path $root 'run-state.json');$h=Read-J (Join-Path $root 'heartbeat.json');$elapsed=((Get-Date)-$started).ToString('hh\:mm\:ss');$age=[Math]::Round(([datetimeoffset]::UtcNow-[datetimeoffset]$h.sampledAt).TotalSeconds,1);Write-Host ("监听次数={0} 时长={1} RunId={2} 状态={3} 阶段={4} 当前组={5} 预热={6} Suite={7} Wave={8} 心跳延迟={9}s"-f$count,$elapsed,$d.Name,$s.status,$s.phase,$h.currentGroupCode,$h.warmupAttempt,$h.suiteState,$h.suiteWave,$age) -ForegroundColor Green;$h|ConvertTo-Json -Depth 8}catch{Write-Host ("等待状态：{0}"-f $_.Exception.Message) -ForegroundColor Yellow};Start-Sleep -Seconds 2}
```

按 `Ctrl+C` 停止监听只会结束当前监听 PowerShell，不会停止调度器或应用。

### 9.5 RabbitMQ 会员业务队列只读查询

```powershell
docker exec rabbitmq1 rabbitmqctl list_queues --formatter json name consumers messages_ready messages_unacknowledged | ConvertFrom-Json | Where-Object { $_.name -like 'membership.*' } | Format-Table name,consumers,messages_ready,messages_unacknowledged -AutoSize
```

该命令只读取队列状态，不会清理任何消息。

### 9.6 关键参数解释

| 参数 | 含义 |
| --- | --- |
| `RunId` | 当前新子 Run，必须唯一，不复用历史目录 |
| `MasterRunId` | 同一逻辑测试链的证据主键 |
| `StartGroupCode` | 当前从哪个正式区段开始 |
| `ExpectedFormalSegmentCount=8` | 当前 Run 应执行完整八段 |
| `ExpectedFormalSegmentCount=0` | 续跑模式不要求当前 Run 自身包含固定段数 |
| `SkipInitialGates` | 仅在复用已有 PostgreSQL 门禁的续跑中使用 |
| `PreviousScenarioListIsAuthoritative` | 传入的 scenario 清单是已经完成且需要保留/精确处理的事实来源 |
| `ExistingPostgresStabilityGatePath` | 复用原 Master 的 PostgreSQL 稳定性证据 |
| `RedisWriteBatchSize=64` | 单 lane 单个 Pipeline 最大 64 条 |
| `RedisWriteLaneCount=6` | 六条稳定散列 FIFO lane |
| `RedisWriteMaximumInflight=384` | 全局逻辑写入在途上限 |

---

## 十、故障接管顺序

1. 读取 `loadtest-output/launcher/current-80k-run-id.txt`，确认监听的是当前 Run，而不是旧 Run。
2. 读取当前 Run 的 `run-state.json` 与 `heartbeat.json`。
3. 心跳年龄小于 10 秒且 PID/命令行匹配时，只继续观察，禁止启动第二套编排。
4. 心跳超过 10 秒时，检查 Orchestrator、Application、Sampler、Suite 四个 PID；不能仅凭旧 JSON 判断存活。
5. 读取 `loadtest-output/launcher/<runId>.stdout.log` 与 `<runId>.stderr.log`。
6. 使用 RabbitMQ 只读命令检查 consumers、Ready、Unacked；不要先执行 purge。
7. 结合 PostgreSQL 订单终态、callback、Redis 工作集合判断是否真正收敛。
8. 只有最后一个正式区段完整 PASS 且 scenario CSV 存在时，才能从下一区段续跑。
9. 预热中断、scenario CSV 缺失、PostgreSQL PID/启动时间改变或采样器失效时，用新 Run ID 重新执行该区段，不能把中断预热算作正式通过。
10. 新恢复应用可以恢复 RabbitMQ 消费，但不会复活旧调度器、旧 Run 心跳或旧 PID。

明确禁止：

- 没有 scenario CSV 就跳段。
- 把旧 Run ID 当作新 Run ID。
- 心跳变旧后直接启动第二套调度器。
- 把 `list_queues` 当作清理命令。
- 把 `purge_queue` 放入常规启动流程。
- 清理所有 `membership.*` 队列或正常 `membership.closing.check.queue`。

---

## 十一、证据索引

### 11.1 40K

- [40K 前三段根目录](../../loadtest-output/soak/membership-order-create-auto-20260827-102027-performance-40k/millisecond-boundary/)
- [40K E-AR 根目录](../../loadtest-output/soak/membership-order-create-resume-ear-5kx8-20260827-135706/millisecond-boundary/)
- [40K 后四段根目录](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/)
- [40K 后四段顶层假阴性 verdict](../../loadtest-output/soak/membership-order-create-resume-hp1-5kx4-20260827-174807/millisecond-boundary/verdict.json)

### 11.2 80K

- [80K 前三段根目录](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-121713/millisecond-boundary/)
- [80K E-AR 根目录](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-160053/millisecond-boundary/)
- [80K 后四段根目录](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/)
- [80K 后四段顶层假阴性 verdict](../../loadtest-output/soak/membership-order-create-capacity-80k-20260828-164946/millisecond-boundary/verdict.json)

### 11.3 问题与设计依据

- [状态机竞态与 REJECTED 关单报告](2026-08-21-membership-payment-realtime-jmeter-test-report.md)
- [调度、最近 PAID 索引与 Hikari 重测交接](2026-08-24-membership-payment-scheduler-index-hikari96-40k-retest-handoff.md)
- [256 并发与日志统计合同](2026-08-25-membership-payment-256-concurrency-40k-retest-handoff.md)
- [双预热、40K/80K 与心跳接管计划](2026-08-26-membership-order-create-double-warmup-40k-80k-retest-handoff.md)
- [Redis 64×6/384 ADR](../architecture/adr-2026-08-26-membership-payment-redis-write-64x6.md)

---

## 十二、准确性边界与最终判定

### 12.1 准确性边界

- 本报告基于已有正式 JSON/CSV、历史运行状态和测试完成后的数据库核验，没有重新执行压测。
- 逐段性能数字来自 `golden-baseline-comparison.json.current`，请求和回调数来自区段级 `verdict.json`。
- 80K 数据库汇总来自测试完成后的人工 SQL 核验；报告编写阶段没有重新连接数据库。
- 历史顶层 FAIL 全部保留，不通过删除或覆盖 verdict 形成 PASS。
- RabbitMQ 队列为空、消费者存在、Redis 工作集合收敛都不是单独充分条件；最终结果以多源证据交叉验证。
- 本轮没有 etcd 故障证据。

### 12.2 最终判定

```text
40K 合同门槛：PASS
40K 黄金基线复现：八段全部 REPRODUCED
40K 八段持续性：全部持续达标

80K 合同门槛：PASS
80K 黄金能力：八段全部 GOLDEN_CAPABILITY_TARGET_REACHED
80K 八段持续性：全部持续达标

数据一致性：PASS
顶层聚合状态：两个后四段续跑 Run 为假阴性 FAIL，报告器兼容性待修复
```

综合验收结论：40K 与 80K 会员订单创建/回调毫秒边界全链路在当前单实例、PostgreSQL 384、Hikari 256/8、Redis 64×6/384、RabbitMQ 48+48 消费者合同下通过。后续工作应修复最终 HTTP 报告器对任意合法续跑段数的支持，但该报告器问题不改变本轮正式区段和最终数据事实。
