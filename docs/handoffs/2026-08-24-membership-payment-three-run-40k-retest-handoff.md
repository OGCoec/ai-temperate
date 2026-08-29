# 会员支付三次 40K 极端边界测试汇总与后续交接

文档日期：2026-08-24。

适用项目：`C:\Users\damn\Desktop\ai-temperate-main`。

文档用途：把本次长上下文中的三次主要正式执行、代码改造、失败门禁、保留证据和下一次接手步骤压缩到一份可独立阅读的交接文档。后续任务应优先读取本文和本文列出的直接证据，不要重新加载整段聊天记录。

> **唯一测试口径：** 本文中的正式测试始终是八区段、每段 5,000 笔、总计 40,000 笔。截图中“每段 50,000、总计 400,000”属于历史画面，不是本轮合同。本文完全排除 W01～W08 和历史回归冒烟。

## 一、当前一页结论

截至本文生成时：

- 当前没有会员支付应用实例或 JMeter 进程在运行。
- 第一轮 40K 完整运行通过，证明了八区段边界裁决、数据库终态、权益、Redis 和 RabbitMQ 可以完整收敛。
- 第二轮在 Lua、退款规则和聚焦日志改造后执行了 7 个区段、形成 35K 数据，但测试期间源码指纹发生变化，整轮按门禁作废，H-AR 未执行。
- 第三轮把 HTTP/Tomcat/Redis 逻辑写入并发降为 256，并使用 128 条 Pipeline 和紧凑 `v=2` 日志；执行到 H-A1 后数据库已经完成 35K 裁决，但 Redis `callback ready` 仍残留 400 条，120 秒内未归零，因此 Runner 正确停止，H-AR 未执行。
- 第三轮冻结证据已经确认：这 400 条全部是 `REFUND_REQUIRED`，PostgreSQL 订单已经 `CLOSED` 且权益已经裁决；Redis 终态订单快照已被删除，退款 finalize Lua 因快照不存在返回 `MISSING`，Callback Worker 又把同一批 claim 重新放回 ready 集合。
- 第三轮失败后没有自动清理数据库或 Redis；35K 订单、35K 回调、Redis 残留、JTL 和日志均应继续视为故障证据，未经新指令不得删除。
- 当前最优先事项不是立刻开始第四次 40K，而是先修复“终态快照已经删除时，退款回调仍被无限重排”的恢复合同，并修复聚焦日志报告器对无 `orderIdB64` 诊断事件的解析问题。

最终状态应表述为：

```text
第一轮：功能 PASS，保留两项测试合同偏差。
第二轮：测试无效，源码指纹在执行中变化。
第三轮：功能 FAIL，Redis callback ready 未收敛；性能数据仅供诊断。
下一轮：尚未开始。
```

## 二、固定正式测试合同

固定测试用户：

```text
70000000000000000 ～ 70000000000039999
```

固定八区段：

| 顺序 | 区段 | 用户序号 | 目标边界 | 数量 |
| ---: | --- | --- | --- | ---: |
| 1 | E-P1 | 00000～04999 | `expiresAt - 1ms` | 5,000 |
| 2 | E-PR | 05000～09999 | `expiresAt -1000ms～-2ms`，500 个点循环 | 5,000 |
| 3 | E-A1 | 10000～14999 | `expiresAt + 1ms` | 5,000 |
| 4 | E-AR | 15000～19999 | `expiresAt +0ms～+998ms`，500 个点循环 | 5,000 |
| 5 | H-P1 | 20000～24999 | `hardCloseAt - 1ms` | 5,000 |
| 6 | H-PR | 25000～29999 | `hardCloseAt -1000ms～-2ms`，500 个点循环 | 5,000 |
| 7 | H-A1 | 30000～34999 | `hardCloseAt + 1ms` | 5,000 |
| 8 | H-AR | 35000～39999 | `hardCloseAt +0ms～+998ms`，500 个点循环 | 5,000 |

共同边界：

- 单一 `loadtest-realtime` 应用实例，端口 `6655`。
- Provider 为本机 `LOCAL_SIMULATOR`，不连接真实 BAR 或真实资金渠道。
- PENDING/CLOSING 消费者各 48，prefetch 为 20。
- 正式预检 120 秒，每区段后稳定窗口 60 秒。
- 最终裁决使用服务端实际 `received_at` 与 `hardCloseAt`，区段名称和计划偏移不能代替真实时间事实。
- Base64URL 订单号大小写敏感，集合与唯一性比较必须使用序数、大小写敏感语义。
- 任一区段发生 Crash、JMeter 失败、源码变化、Redis/RabbitMQ 不收敛或 DLQ 时立即停止，后续区段不得拼接为 PASS。

第三轮及下一轮的削峰参数：

| 参数 | 固定值 |
| --- | ---: |
| 创建 HTTP 并发 | 256 |
| 回调 HTTP 并发 | 256 |
| Tomcat accept/max-connections/threads.max | 256/256/256 |
| Redis 逻辑写入 maximum-inflight | 256 |
| Redis Pipeline 单批 | 128 |
| Pipeline flush window | 1ms |
| Redis 写入总超时 | 30s |
| Rabbit Channel cache/requested-channel-max | 256/512 |

这些数值表示应用层并发和批量边界，不表示 Redis、Lettuce、Hikari 或 JDBC 存在 256 条物理连接。

## 三、三次主要执行汇总

| 轮次 | Run ID | 主要配置 | 实际范围 | 最终结论 | 主要发现 |
| --- | --- | --- | --- | --- | --- |
| 第一轮 | `membership-millisecond-boundary-20260823-224500` | HTTP 4096；48×2 消费者；全量长格式日志 | 8 段，40K | PASS | 40K 终态、权益、Redis、RabbitMQ 全收敛；发现 3～12 秒 Java 端 Redis 等待和两个测试合同偏差 |
| 第二轮 | `membership-lua-refund-focused-retest2-20260824-130724` | Lua/Pipeline/退款规则改造；四操作聚焦日志 | 执行 7 段，35K；H-AR 未开始 | INVALID | H-A1 最终证据阶段发现源码指纹变化，整轮作废 |
| 第三轮 | `membership-payment-256-40k-retest-20260824-170500` | HTTP/Tomcat/Redis 在途 256；Pipeline 128；紧凑 `v=2` 日志 | 执行 7 段，35K；H-AR 未开始 | FAIL | H-A1 数据库已收敛，但 Redis callback ready 残留 400 条 |

`membership-lua-refund-focused-retest-20260824-1235` 和 `...-1238` 是第二轮前的短暂启动/预检尝试，不计入上述三次主要执行；其中一轮没有正式区段，另一轮只进入 E-P1。它们不能作为正式性能结论。

## 四、第一轮：完整 40K 基线

证据根目录：

```text
loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/
```

正式结果：

| 项目 | 数量/结果 |
| --- | ---: |
| 唯一订单/回调 | 40,000/40,000 |
| APPLIED | 24,987 |
| REFUND_REQUIRED | 15,013 |
| 未决 PENDING_PAYMENT/CLOSING | 0 |
| Redis 四个工作集合 | 0 |
| Rabbit Ready/Unacked/DLQ | 0/0/0 |
| 最终 Runner/PostgreSQL | PASS/PASS |

这一轮的正式价值：

- 八个边界区段全部执行并按实际微秒时间正确裁决。
- 没有订单永久停留在 PENDING_PAYMENT 或 CLOSING。
- Redis 与 RabbitMQ 最终归零。
- 证明 `expiresAt` 后、`hardCloseAt` 前仍可合法 APPLIED；到达或越过 `hardCloseAt` 才属于退款侧。

这一轮的限制和问题：

1. 退款侧 `provider_trade_no` 的最终 SQL 曾错误要求订单与回调都非空且相等，随后为满足错误断言增加过订单字段回填。业务最终确认的正确合同是：
   - APPLIED：订单和回调流水号非空且一致。
   - REFUND_REQUIRED：订单流水号为空，回调保留已验真的交易号。
   - NOT_GRANTED：订单流水号为空，回调按事实保留。
2. JMeter 曾把小数微秒写入最终验证器的 `BIGINT` 字段；后续已改为整数微秒。
3. 全量长格式状态机日志约 563 MiB，I/O 和可读性成本过高。
4. 已明确保存的旧慢锚点全部属于 Java 端 `ORDER_CREATE` 完整等待，不等于单条 Lua 的服务端执行时间：

| 样本 | totalMs | otherRedisMs |
| --- | ---: | ---: |
| A | 3513.624ms | 2460.766ms |
| B | 10940.083ms | 10542.682ms |
| C | 11968.412ms | 10027.624ms |

## 五、第二轮：Lua/退款/聚焦日志重测

证据根目录：

```text
loadtest-output/soak/membership-lua-refund-focused-retest2-20260824-130724/millisecond-boundary/
```

第二轮在以下方面做了调整：

- 将订单快照、Provider 结果等 Redis 路径从模糊 `otherRedisMs` 中拆分。
- 引入或验证 Lua 轻量化与 Pipeline 路径。
- 修正退款业务合同：最终 `REFUND_REQUIRED/NOT_GRANTED` 的订单 `provider_trade_no` 为空，回调保留交易事实。
- 专用日志重点记录 `ORDER_CREATE`、`PAYMENT_ATTEMPT`、`RABBIT_PENDING`、`RABBIT_CLOSING`，不再要求 14 阶段全量矩阵。

实际执行：

- E-P1、E-PR、E-A1、E-AR、H-P1、H-PR 的区段 verdict 为 PASS。
- H-A1 已产生测试事实，但在 `final-evidence` 阶段检测到源码指纹变化。
- Run verdict：`FAIL`，消息为 `Source fingerprint changed during the wave.`。
- H-AR 未开始。
- 该轮形成的 7 份清单总计 35,000 个大小写敏感唯一订单，后续已被第三轮前置流程精确复位。

这一门禁是正确的：测试过程中源码变化后，前后区段已不再属于同一个冻结构建，不能继续执行 H-AR，也不能把 35K 结果升级为正式 PASS。

## 六、第三轮：256 并发、Pipeline 128、紧凑日志

### 6.1 构建和前置基线

正式 Run ID：

```text
membership-payment-256-40k-retest-20260824-170500
```

构建证据：

| 项目 | 值 |
| --- | --- |
| Git HEAD | `45328b49b1907534e4c5f1cd36cf1a563a03db48` |
| 源码指纹 | `50ded6440bbcdd2a2e7874fe890cfb17620d6abadd67eb937675ca6895d3118c` |
| JAR SHA-256 | `a51eabbd482f258c8db9693db5506cb312c82b6616dbed83a33b747d4aa61c9f` |
| Java | 21.0.10 LTS |

进入正式流量前已经完成：

- 使用第二轮七份大小写敏感清单精确清理 35K Redis 订单状态、35K 回调和 35K 订单。
- 仅把固定 40K 用户恢复为 FREE；另外 16 个非测试额度账号保持不变。
- Identity/Profile/ACTIVE/Quota/精确 FREE 均为 40K，固定用户订单/回调为 0/0。
- Rabbit Ready/Unacked/DLQ 和 Redis 工作集合均为 0。
- 8 个业务预热订单执行并精确清理。
- 120 秒稳定预检通过。

前置复位和构建证据位于第三轮运行根目录，不能用第一轮旧 40K CSV 替代第三轮事实。

### 6.2 实际执行结果

执行顺序和状态：

```text
E-P1 PASS
E-PR PASS
E-A1 PASS
E-AR PASS
H-P1 PASS
H-PR PASS
H-A1 业务请求和数据库裁决完成，但 Redis 收敛门禁 FAIL
H-AR NOT_STARTED
```

冻结时数据库与基础设施事实：

| 项目 | 数量/结果 |
| --- | ---: |
| 订单/回调 | 35,000/35,000 |
| 活动订单 | 0 |
| 未裁决订单/回调 | 0/0 |
| 已裁决订单 | 35,000 |
| APPLIED | 24,843 |
| REFUND_REQUIRED | 10,157 |
| NOT_GRANTED | 0 |
| Redis callback ready | 400 |
| Redis callback processing | 0 |
| Redis order dirty/processing | 0/0 |
| Rabbit Ready/Unacked/DLQ | 0/0/0 |

Runner 的直接失败消息：

```text
Redis membership queues did not drain within 120s: callbackReadySize=400
```

这不是 JMeter HTTP 失败或 RabbitMQ 积压导致的停止。H-A1 的 JMeter 请求和 PostgreSQL 最终裁决已经完成，失败发生在区段最终 Redis 工作集合收敛门禁。

### 6.3 已确认的 400 条残留事实

400 条 ready 回调全部满足：

| 事实 | 数量/值 |
| --- | ---: |
| callback resolution | `REFUND_REQUIRED` |
| order status | `CLOSED` |
| order entitlement resolution | `REFUND_REQUIRED` |
| order provider_trade_no 为空 | 400 |
| callback provider_trade_no 非空 | 400 |
| callback data Key 存在 | 400 |
| order snapshot Key 存在 | 0 |

这同时证明第三轮数据已经遵守新的退款流水号业务合同：退款订单字段为空，回调仍保留交易事实。

### 6.4 已确认的失败链

```text
退款所需的 PostgreSQL 事务已经提交，订单成为 CLOSED
    -> order_persist_complete.lua 完成终态持久化后 UNLINK 订单快照
    -> 迟到或重试的 REFUND_REQUIRED callback claim 再次进入 finalize
    -> finalize_refund_required.lua 发现 snapshot 不存在，返回 MISSING
    -> PaymentCallbackBatchServiceImpl 认为 finalization 不完整并抛出异常
    -> Callback Worker 将 claim 重新放回 callback ready
    -> PostgreSQL 已经完成，但 Redis ready 中的同一批事实无法归零
```

直接相关代码：

- `ai-temperate-service/src/main/resources/lua/membership-payment/order_persist_complete.lua`
- `ai-temperate-service/src/main/resources/lua/membership-payment/finalize_refund_required.lua`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/callback/impl/PaymentCallbackBatchServiceImpl.java`

准确问题不是“数据库没有写成功”，而是恢复逻辑把已完成的 PostgreSQL 终态和已删除的 Redis 临时快照组合误判为仍需重试。修复时必须让重复/迟到退款 finalization 能根据可信持久化事实幂等完成，或者调整终态快照的删除时机；在没有测试锁定一致性边界前，不应直接选择其中一种方案。

### 6.5 紧凑日志实际结果

日志文件：

```text
logs/membership-payment-state-machine.log
```

冻结时：

```text
大小：16,436,344 bytes，约 15.68 MiB
行数：70,490
```

操作分布：

| operation | 行数 |
| --- | ---: |
| ORDER_CREATE | 35,183 |
| PAYMENT_ATTEMPT | 35,008 |
| RABBIT_CLOSING | 110 |
| SIMULATED_CALLBACK_RECEIVE | 81 |
| RABBIT_PENDING | 68 |
| CALLBACK_WORKER_BATCH | 40 |

其中：

- `ORDER_CREATE` 包含 35,008 条成功主记录和 175 条失败诊断记录。
- 全日志共有 175 条 `out=FAILED`。
- 215 条记录没有 `orderIdB64`；其中包括 175 条订单创建失败诊断和 40 条 Callback Worker Batch 诊断。
- 主操作使用紧凑 `v=2` 单行格式，空间压缩已经生效。
- 当前只是 35K 中止轮，不能把 15.68 MiB直接当作完整 40K 的最终体积 verdict。按行数线性外推约 17.9 MiB，但 H-AR 的慢/失败诊断数量未知，因此只能作为参考。

第三轮证明日志已经从旧轮约 563 MiB 大幅下降，但同时暴露两个后续问题：

1. 聚焦报告器对每条事件过早强制要求 `orderIdB64`，因此遇到合法的失败/批处理诊断时生成报告失败：`Focused timing event is missing required field: orderIdB64`。
2. `SIMULATED_CALLBACK_RECEIVE` 和 `CALLBACK_WORKER_BATCH` 因超过 1 秒而进入诊断日志。它们符合“非主操作只保留慢/失败/NACK”的通用选择规则，但报告必须把它们标记为筛选后的诊断样本，不能计算成全量百分位；如果用户只想保留 Rabbit 诊断，应把这两类移到普通日志或单独诊断文件，而不是静默丢弃异常。

## 七、当前应保留的故障证据

第三轮根目录：

```text
loadtest-output/soak/membership-payment-256-40k-retest-20260824-170500/millisecond-boundary/
```

最重要文件：

- `verdict.json`：Run 级 FAIL 和直接原因。
- `soak-state.json`：Runner 已停止。
- `failure-evidence-summary.json`：35K 数据库事实、400 条 Redis 残留和根因摘要。
- `build-evidence.json`：Git、源码指纹、JAR、Java 和 256/128 参数。
- `scenario-orders-completed-35k.csv`：第三轮 7 个已执行区段的 35K 精确清单。
- `H-A1/verdict.json`：失败区段证据。
- `H-A1/settlement-wait.csv`：H-A1 等待与收敛过程。
- 各区段 `scenario-orders.csv`、`request-results.csv`、`results.jtl` 和基础设施快照。
- `logs/membership-payment-state-machine.log`：第三轮紧凑原始计时日志；在完成解析和归档前不得删除或截断。

当前保留策略：

- 不自动删除第三轮 35K 订单或 35K 回调。
- 不把固定 40K 用户再次恢复 FREE。
- 不清理 400 条 ready claim 或相关 Redis Key。
- 不重置 Redis SLOWLOG。
- 不覆盖固定名称的日志和聚焦报告文件。
- 不运行下一轮 JMeter。

## 八、下一次接手必须先完成的工作

### 8.1 先修复退款 finalization 的终态缺快照恢复合同

使用 TDD 先补充至少以下场景：

1. PostgreSQL 订单已 `CLOSED`、权益已 `REFUND_REQUIRED`，Redis 订单快照不存在，callback claim 仍在 processing。
2. 同一 callback finalize 重复调用时，不重新发权益、不重复退款、不产生永久 requeue。
3. Callback Marker、Provider 模拟结果、callback processing/ready 和 dirty 集合最终正确清理。
4. 尚未完成 PostgreSQL 裁决时，缺快照不得被误当作成功，必须保留安全恢复路径。
5. APPLIED、NOT_GRANTED、取消和正常 CLOSED 的现有幂等合同不回归。

修复必须明确“可信事实源”以及事务提交、Redis 清理和 ACK/requeue 的顺序，不能用无边界重试掩盖缺快照。

### 8.2 修复聚焦报告解析合同

报告器至少应区分：

- `ORDER_CREATE/PAYMENT_ATTEMPT` 成功主样本：必须有合法 22 字符 `orderIdB64`。
- 主操作在订单尚未创建时失败：允许没有订单号，但必须保留 `outcome/errorClass`，并单独计入失败诊断。
- Rabbit/Callback Batch 诊断：允许按 message、trace 或批次聚合，不能伪造订单号。
- 报告必须先按本轮 `runId` 和 `formalStartedAtEpochMs` 过滤，再执行字段完整性校验。
- 全量百分位只允许来自两个 HTTP 主操作的成功/完整主样本；慢诊断不能冒充全量分布。

### 8.3 解释 175 条 ORDER_CREATE FAILED

在下一轮清理数据前，从原日志和普通应用日志关联这 175 条 `MembershipPaymentException`：

- 确认它们是否是 JMeter 有界重试前的瞬时失败。
- 确认是否产生重复订单、错误幂等结果或 bulkhead/submit timeout。
- 将可恢复重试和真正业务失败分开统计。
- 在原因没有解释前，不能宣称第三轮 HTTP 主操作性能 PASS。

### 8.4 完成修复后的验证与冻结

只有相关单元、Redis 7.4 集成、Web/YAML、PowerShell 合同和 Spring 上下文验证通过后，才重新构建 JAR并保存新 Git HEAD、源码指纹和 JAR SHA-256。测试期间禁止修改源码。

项目规范要求测试和基础设施连接属于第二阶段动作；下一次实际执行测试、构建、清理数据库或 Redis 前，仍需取得当次明确授权。

## 九、第四次正式执行前的精确复位顺序

完成代码修复、验证、打包和指纹冻结后，才允许准备数据。当前清理集合应以第三轮 `scenario-orders-completed-35k.csv` 和数据库事实的双向相等校验为准，不能使用第一轮旧 40K 清单直接删除第三轮数据。

固定顺序：

1. 确认应用、JMeter 和会员写入进程全部停止。
2. 确认第三轮 35K 清单与固定用户当前订单集合大小写敏感、双向完全相等。
3. 保存 400 条 ready claim 和相关 Key 的修复前证据。
4. 仅按 35K 订单/回调清单精确 `UNLINK/ZREM` 关联 Redis 状态；禁止 `KEYS *`、`FLUSHDB` 或删除整个全局 ZSet。
5. 通过受控 Fixture 先删除 35K 回调，再删除 35K 订单。
6. 仅将固定 40K 用户恢复为 FREE；其他用户和另外 16 个额度账号保持不变。
7. 清除固定用户会员资料缓存。
8. 验证 Identity/Profile/ACTIVE/Quota/精确 FREE 均为 40K，固定用户订单/回调为 0/0。
9. 验证 Redis 工作集合和 Rabbit Ready/Unacked/DLQ 全部为 0。
10. 使用新的唯一 Run ID 启动单一 JAR，执行 8 个预热订单和精确清理。
11. 完成 120 秒稳定预检，再重置 SLOWLOG 并开始正式八区段。

正式执行仍只允许：

```text
E-P1 -> E-PR -> E-A1 -> E-AR -> H-P1 -> H-PR -> H-A1 -> H-AR
```

每段 5,000，总计 40,000；区段间 60 秒；不执行 W01～W08。

## 十、下一任务可直接使用的简短上下文

后续 Codex 任务可以直接使用下面这段，不必粘贴整段旧聊天：

```text
请先阅读：
docs/handoffs/2026-08-24-membership-payment-three-run-40k-retest-handoff.md

当前第三轮 Run ID：membership-payment-256-40k-retest-20260824-170500。
目前没有应用或 JMeter 在运行；第三轮在 H-A1 后停止，H-AR 未开始。
PostgreSQL 冻结为 35K 已裁决订单/回调；Redis callback ready 残留 400 条，全部是 CLOSED + REFUND_REQUIRED，订单快照已不存在。
先不要清理数据或重启 40K。请先用 TDD 修复终态订单快照缺失时 refund finalization 被永久 requeue 的问题，再修复聚焦报告器对无 orderIdB64 诊断事件的解析合同，并解释 175 条 ORDER_CREATE FAILED。
修改完成后列出验证和精确复位计划，获得明确授权后再执行下一轮 8×5K；禁止 W01～W08。
```

## 十一、结论边界

本文只完成事实归并和交接：

- 没有修改退款 finalization 业务代码。
- 没有修改聚焦报告器。
- 没有运行编译、测试或重新打包。
- 没有清理 PostgreSQL、Redis、RabbitMQ、日志或 JTL。
- 没有启动第四次 JMeter。

后续结论必须继续区分：

```text
Java 端 Redis 完整等待 != Redis SLOWLOG 单条命令执行时间
数据库已裁决 != Redis 工作集合已经收敛
JMeter 请求成功 != 整个异步状态机已经通过最终门禁
35K 中止轮诊断数据 != 完整 40K 正式性能结论
```
