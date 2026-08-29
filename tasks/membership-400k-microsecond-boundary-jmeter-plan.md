# 40 万会员支付微秒边界 JMeter 测试计划

## 1. 目标和已确认决策

- 正式账号总数为 400,000，序号为 `000000～399999`，真实账号 ID 为
  `70000000000000000～70000000000399999`。
- 不保留原来 500 人作为独立样本；400,000 人全部参加正式测试并逐笔裁决。
- 仍使用 8 个业务区段，每个区段 50,000 人；4 个波次只作为 Token 和证据分组，实际执行、裁决和失败重跑必须以单个区段为单位。
- 测试必须由 JMeter 非 GUI 模式执行；JMX/JSR223 负责订单创建、支付发起、精确调度、真实本地 HTTP 回调和终态观察。
- 只连接 `127.0.0.1:6655` 的 `loadtest-realtime` 本地实例和 `LOCAL_SIMULATOR`，不得连接公网支付平台或 BAR。
- 业务时间统一使用 UTC 六位小数；数据库业务事实和最终比较精确到微秒，调度目标偏移仍以毫秒定义。
- 回调是否发放权益不能只判断 `paid_at < expires_at`，必须按软关闭窗口和 `hardCloseAt` 规则裁决。
- 任一区段出现失败时不结束整个任务。先暂停 JMeter 新请求并冻结失败证据，由 Codex 在同一任务内继续监控、诊断和修复；受影响区段使用新 Run ID 重跑，通过后再进入下一区段。
- 八个区段严格顺序执行；一个区段完成全部终态和逐笔裁决后，至少静默观察 60 秒才能开始下一区段。

## 2. 40 万账号的 8 个区段

设区段内位置为 `p = userId - firstUserId`，范围 `0～49,999`。范围型区段的 500 个原始毫秒目标重复 100 次：

```text
offsetIndex = p % 500
```

这可保持原测试的 `-1000～-2ms` 或 `0～+998ms` 边界范围，不会错误地把 50,000 人扩展成约 100 秒的偏移范围。

| 区段 | 波次 | 用户序号 | 真实账号 ID | 参照边界 | 目标偏移 |
| --- | --- | --- | --- | --- | --- |
| E-P1 | E-PRE | 000000～049999 | 70000000000000000～70000000000049999 | expiresAt | 全部 `-1ms` |
| E-PR | E-PRE | 050000～099999 | 70000000000050000～70000000000099999 | expiresAt | `-1000 + 2 × (p % 500) ms` |
| E-A1 | E-AFTER | 100000～149999 | 70000000000100000～70000000000149999 | expiresAt | 全部 `+1ms` |
| E-AR | E-AFTER | 150000～199999 | 70000000000150000～70000000000199999 | expiresAt | `0 + 2 × (p % 500) ms` |
| H-P1 | H-PRE | 200000～249999 | 70000000000200000～70000000000249999 | hardCloseAt | 全部 `-1ms` |
| H-PR | H-PRE | 250000～299999 | 70000000000250000～70000000000299999 | hardCloseAt | `-1000 + 2 × (p % 500) ms` |
| H-A1 | H-AFTER | 300000～349999 | 70000000000300000～70000000000349999 | hardCloseAt | 全部 `+1ms` |
| H-AR | H-AFTER | 350000～399999 | 70000000000350000～70000000000399999 | hardCloseAt | `0 + 2 × (p % 500) ms` |

每个范围型区段中，每一个毫秒目标有 100 个用户。每区段 GO、PLUS、PRO、MAX 各 12,500 人；分配时保证每个范围目标下四个套餐各 25 人，避免套餐与某一偏移位置绑定。

## 3. Provider Trade No 区段前缀

每笔模拟 Provider 交易号统一使用：

```text
<groupCode>-MMB-<runId>-<userId>
```

示例：

```text
E-P1-MMB-membership-millisecond-boundary-20260823-170000-70000000000000000
H-AR-MMB-membership-millisecond-boundary-20260823-170000-70000000000399999
```

必须满足：

- 最左侧前缀只能是八个固定区段码之一。
- 同一个原始字符串同时写入 `trade_no`、`api_trade_no`，并参与 HMAC 原文签名；不得签名后再添加前缀。
- `membership_order.provider_trade_no` 与 `membership_payment_callback.provider_trade_no` 必须完全一致。
- 生成前检查 UTF-8 长度和字符长度不超过数据库 `VARCHAR(128)`。
- 400,000 个值全局唯一，并继续由两张表的唯一约束兜底。
- `param=groupCode` 保留，最终验证要求它与交易号前缀相互吻合。

## 4. 正确的业务裁决

每笔订单计算：

```text
hardCloseAt = expires_at + 5 minutes
```

基础时间不变量：

```text
payment_started_at < expires_at
paid_at >= payment_started_at
paid_at <= received_at
```

最终权益规则：

```text
received_at < hardCloseAt   -> APPLIED
received_at >= hardCloseAt  -> REFUND_REQUIRED
```

因此：

- `paid_at >= expires_at` 或 `received_at >= expires_at` 本身不是失败；它表示回调进入了五分钟软关闭窗口。
- Redis 仍显示 `PENDING_PAYMENT` 或 `CLOSING` 不能覆盖业务时间事实；状态迁移消息可能延迟。
- `closing_deadline_at=NULL` 在付款先于 CLOSING 状态持久化时合法；若非空，则必须等于计划 `hardCloseAt`。
- `received_at == hardCloseAt` 必须严格属于退款侧。
- H-P1/H-PR 在高负载下可能因真实调度漂移跨过边界，最终数量必须动态汇总，不固定断言 APPLIED/REFUND_REQUIRED 的总数。

## 5. 正式运行前需要改造的 JMeter 合同

### 5.1 数据合同

- 把 groups CSV 从 8×500 改为 8×50,000，并新增或明确 `offsetCycleSize=500`。
- 每个波次读取 100,000 个 Token、两个区段；删除脚本中的 `1,000`、`500`、`125` 等硬编码。
- Token CSV、场景 CSV 和结果 CSV 使用流式读取/写入，不使用 `Files.readAllLines` 一次构造大量 Map。
- 证据写入使用单写线程加有界队列和批量 flush，避免 100,000 次同步打开/追加文件。
- 不一次创建 100,000 个 Future；使用有界提交队列，确保待处理对象数量和内存占用有明确上限。

### 5.2 并发模型

- `CREATION_CONCURRENCY=4096` 保留为可配置上限，不再创建 4,096 个平台线程。
- JMeter 所用 JDK 必须为 21；订单创建和回调 I/O 使用虚拟线程或异步 HTTP 客户端。
- 用 Semaphore 限制真实 in-flight 请求数，分别记录 `submitted`、`inFlight`、`completed`，禁止把排队任务数量称为实际并发。
- 订单创建、回调发送、终态查询使用三个独立容量池，避免观察请求被回调洪峰饿死。
- 回调调度不得依赖只有 4 个线程的 `ScheduledThreadPoolExecutor` 执行 100,000 个任务；调度器只管理时间桶，到点后交给有界发送池。
- HTTP 连接池启用 keep-alive，限制每目标连接数，并在证据中记录连接失败、超时、重试和最终结果。

### 5.3 JTL 和请求级证据

- JMeter 使用非 GUI 模式，JTL 禁止保存响应体，只保存时间、label、responseCode、success、failureMessage、延迟和线程信息。
- Runner 必须同时验证 JMeter 退出码、JTL 所有行、固定 sampler 名称和请求级 CSV。
- 每个内部 HTTP 请求都必须有请求级结果；不得只用一个成功的外层 JSR223 sampler 掩盖内部失败。
- 失败响应体只截取脱敏后的短摘要，Access Token 不进入 JTL、CSV、日志或错误消息。

## 6. 4096 并发容量结论和校准方式

当前本机与应用边界为：16 个逻辑处理器、约 15.8 GB 内存、Tomcat `max-connections=2000`、`max-threads=512`、PostgreSQL `max_connections=100`；项目未显式配置 Hikari 最大池时，默认连接池还会明显小于 PostgreSQL 上限。当前回调驱动的上限只有 128。

结论：

- 4096 对这台机器已经足够，甚至偏大；它不是 400,000 用户的 1:1 并发需求。
- 当前代码中的 4096 个平台线程容易增加线程栈内存和上下文切换，未必比 1,024 或 2,048 更快。
- Tomcat 连接上限为 2,000，所以不调整服务端时不可能有 4,096 条连接同时贯通。
- 订单最终还会受数据库连接池限制；把提交线程从 2,048 提高到 4,096 很可能只是增加等待。
- 当前回调并发 128 才是边界回调洪峰的直接限制；只提高订单创建并发不会让 50,000 个回调更接近同一毫秒到达。

在正式写数据前，使用现有 400,000 Token 对只读会员套餐接口执行 JMeter 容量标定，不保留任何专门样本账号：

```text
512 -> 1024 -> 2048 -> 4096
```

每档固定时长并记录吞吐、p95/p99、错误率、CPU、可用内存、GC、Tomcat 活跃线程/连接和连接拒绝。只有下一档吞吐仍有明确提升且错误为零时才继续。正式订单创建取“最高稳定档”，4096 仍保留为上限；若 2,048 已达到平台吞吐上限，则不得为了数字好看强行使用 4,096。

回调发送池单独从 128、256、384 到 512 标定，并至少为终态查询和健康监控保留 64 个 Tomcat 工作线程。若确实要测试超过 2,000 个真实同时连接，必须另行调整 Tomcat、操作系统和数据库容量；这不是单改 JMeter 数字即可实现。

## 7. 预检和冻结

正式运行前依次完成：

1. 确认 400,000 个 identity/profile/quota 全部存在且均为 FREE，订单和回调均为 0。
2. 确认四个 100,000 Token 文件覆盖且只覆盖整个账号区间；检查 exp，但不得打印 Token。
3. 确认只有一个 `6655` 应用实例，不存在 `8080` 实例。
4. 确认 Provider 为 `LOCAL_SIMULATOR`，没有公网支付调用配置。
5. 确认 Redis v2 订单、Marker、Ready/Processing、Dirty/Processing 无残留。
6. 确认 RabbitMQ 两个会员队列各一个消费者，Ready、Unacked、DLQ 都为 0。
7. 确认两张表全部十个业务时间字段为 `TIMESTAMPTZ(6)`，微秒编解码合同通过。
8. 确认八个交易号前缀、长度、全局唯一、签名原文和两表一致性合同通过。
9. 执行 JTL 失败门禁、并发观察、50,000 人循环偏移和套餐均匀分布合同测试。
10. 计算并冻结 Java、SQL、Lua、Groovy、JMX、PowerShell、YAML 和断言文件的 `sourceFingerprint`。
11. 所有静态只读预检并行执行，随后完成连续 60 秒空载稳定观察；期间源码指纹、队列、Redis 和实例数不得变化。任何变化都会重置 60 秒稳定计时器，以避免用固定长等待浪费健康环境的时间。

## 8. 八个正式区段和一分钟间隔

四个 Token/证据波次仍然是：

```text
E-PRE   = E-P1 + E-PR       100,000
E-AFTER = E-A1 + E-AR       100,000
H-PRE   = H-P1 + H-PR       100,000
H-AFTER = H-A1 + H-AR       100,000
```

但 JMeter 必须按以下八个独立执行单元顺序运行：

```text
E-P1 -> 60s -> E-PR -> 60s -> E-A1 -> 60s -> E-AR
     -> 60s -> H-P1 -> 60s -> H-PR -> 60s -> H-A1
     -> 60s -> H-AR
```

最后一个 H-AR 完成后不需要为了形式再等待一分钟，直接进入 400,000 行最终合并验证。

每个 50,000 人区段执行流程：

1. 检查所属 Token 文件的新鲜度；不足以覆盖本区段最长 10 分钟窗口、收敛期和后续一分钟间隔时，即时重签对应 Token。
2. 保存区段前 PostgreSQL、Redis、RabbitMQ、JVM、Tomcat 和源码指纹证据。
3. 使用本区段正式账号执行 250 个 TEAM 负向探针；这些账号不被保留为样本，探针完成后仍创建合法个人订单。
4. 以标定后的最高稳定创建并发创建 50,000 笔订单并发起 50,000 次支付。
5. 为每笔订单计算 `expiresAt`、`hardCloseAt`、毫秒目标和微秒证据；校验本区段正好 50,000 笔。
6. 到达目标时间后发送 50,000 次本地真实 HTTP 回调；Provider 交易号使用所属区段前缀。
7. 每两秒监控一次订单、回调、权益、Redis 队列、RabbitMQ Ready/Unacked/DLQ、JVM、Tomcat 和数据库连接等待。
8. 按收敛而非固定睡眠等待；连续三次观察无变化且仍未收敛时进入自动诊断，不能把超时直接当 PASS。
9. 解析 JTL 和请求级结果，验证 50,000 创建、50,000 支付发起、50,000 回调及观察请求没有静默失败。
10. 执行 50,000 行 SQL 逐笔裁决并输出本区段时间戳证据、漂移分布和套餐汇总。
11. 保存区段后全部证据。只有本区段通过后才开始一分钟静默间隔。
12. 一分钟内继续每两秒监听。只有连续 60 秒保持订单终态、队列清空、Redis 无积压、JVM/数据库无异常且源码指纹不变，才能进入下一区段；发现异常时立即转入自动恢复，不启动下一区段。

## 9. 持续监听和自动恢复

JMeter 运行时由当前 Codex 任务持续监听，不启用子代理。Codex 进程在预检、区段执行、一分钟间隔、自动修复和重跑期间都不能退出。监控文件至少每两秒输出一次心跳，并包含阶段、区段、已提交、in-flight、成功、失败、订单数、回调数、终态数和最后进展时间。

任何 HTTP、JTL、SQL、业务逻辑、队列、Redis、JVM 或进程失败时：

1. 立即停止提交新的订单或回调；Codex 监控本身继续运行，不退出整个测试任务，也不删除现场。
2. 冻结当前 JTL、CSV、日志、数据库计数、队列和 Redis 证据。
3. 自动区分测试器异常、容量异常、时间精度异常和业务逻辑异常。`paid_at >= expires_at` 但 `received_at < hardCloseAt`、处理中短暂状态滞后和可解释的调度漂移不是业务异常。
4. 测试器异常直接修改 JMX、Groovy、PowerShell、SQL verifier 或证据逻辑；确认违反既定会员支付规则时，可直接修改本项目会员支付 Java、SQL 或 Lua 代码，但不得扩张到无关业务。
5. 修改后执行相应的最小合同验证、重启必要实例并计算新源码指纹。
6. 清理受影响区段产生的数据，使用新 Run ID 和新鲜 Token 重跑该区段；不得在修改源码后从失败位置续跑，也不得把两次运行拼接成一个 PASS。
7. 重跑通过并完成一分钟稳定观察后，继续剩余区段。

## 10. 每笔微秒证据

最终 CSV 每行至少包含：

- `run_id`、`wave_code`、`group_code`、`user_id`、`target_tier`、`order_id`、`provider_trade_no`。
- 订单的 `payment_started_at`、`expires_at`、`closing_deadline_at`、`paid_at`、`entitlement_resolved_at`、`created_at`、`updated_at`。
- 回调的 `paid_at`、`received_at`、`resolved_at`。
- `hard_close_at`、`target_at`、`dispatch_started_at`、`dispatch_completed_at`。
- `dispatch_drift_micros`、`received_from_expires_micros`、`received_from_hard_close_micros`。
- Redis 观察状态、最终数据库状态、实际 resolution、期望 resolution 和逐笔 verdict。

时间格式固定为：

```text
2026-08-23T18:22:00.573421Z
```

## 11. 最终验收

- 400,000 个用户各有且只有一笔本轮订单。
- 400,000 笔订单各有且只有一笔回调。
- 八个区段各 50,000 笔，四个波次各 100,000 笔。
- 每区段四个个人套餐各 12,500 笔；范围区段每个毫秒目标共 100 笔且每套餐 25 笔。
- 八个 `provider_trade_no` 前缀各 50,000 笔，全部唯一，订单与回调完全一致。
- 没有 PENDING、CLOSING、未决权益、Ready、Unacked 或 DLQ 残留。
- 每笔 APPLIED/REFUND_REQUIRED 与 `received_at` 和 `hardCloseAt` 的微秒比较一致。
- APPLIED 用户获得目标套餐；REFUND_REQUIRED 用户保持 FREE。
- JMeter 退出码、JTL、请求级 CSV、SQL verdict 和源码指纹全部通过。
- 输出完整 400,000 行 `final-timestamp-evidence.csv`，并另外输出区段、套餐、目标偏移和实际漂移统计。

## 12. 时间预期

E 波至少需要等待约 5 分钟边界，H 波至少需要等待约 10 分钟边界；100,000 笔订单创建和最终收敛还会额外占用时间。四波正式运行应按小时级任务规划，不能按原 4,000 人测试的分钟级耗时估算。
