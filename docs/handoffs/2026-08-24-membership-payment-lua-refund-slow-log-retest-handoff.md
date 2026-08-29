# 会员支付 Lua 与退款逻辑改造后 40K 慢绿字重测交接文档

> **历史文档警告：** 本文已于 2026-08-25 被 [会员支付 256 并发 40K 重测交接文档](./2026-08-25-membership-payment-256-concurrency-40k-retest-handoff.md) 取代。本文中的 4096 HTTP 并发、旧日志过滤规则和旧性能门禁不再适用于下一轮正式重测；本文仅用于保留历史分析，不得作为新的执行入口。

文档日期：2026-08-24。

适用项目：`C:\Users\damn\Desktop\ai-temperate-main`。

文档状态：待按本文完成重测工具适配后执行；本文没有代表性能重测已经通过。

> 2026-08-24 第二轮执行口径更新：本文后续“只记录超过 1 秒、失败或 NACK”的旧慢阈值方案已被用户最新确认覆盖。正式第二轮固定在原计时代码位置逐次输出 `ORDER_CREATE`、`PAYMENT_ATTEMPT`、`RABBIT_PENDING`、`RABBIT_CLOSING`，不判断快慢；白名单外操作即使失败或 NACK 也不进入专用计时日志。当前实现与验收以 `New-MembershipPaymentFocusedTimingReport.ps1` 和 `Start-MembershipMillisecondBoundarySuite.ps1` 的四操作合同为准，禁止再恢复旧 14 阶段矩阵门禁。

## 一、最终决定

本轮不再为每个订单输出 PENDING 0～8、CLOSING 0～4 等全部状态机明细绿字。

会员支付结构化绿字统一改为：

```text
只记录 totalMs >= 1000ms 的慢事件；
失败事件和 NACK 事件不受慢阈值限制，必须保留；
正常且 totalMs < 1000ms 的事件不记录，也不抽样。
```

重点观察 `ORDER_CREATE` 和 `PAYMENT_ATTEMPT`，同时保留其他操作中真正超过 1 秒的慢事件。这样既能捕捉改造前曾出现的 3～12 秒 Java 端 Redis 等待，又不会因为 40,000 个订单的全量状态机日志反过来干扰被测系统。

退款裁决、状态机终态和权益正确性不再依赖全量绿字证明，而由 PostgreSQL 逐订单最终证据、Redis 工作集合、RabbitMQ 队列/DLQ 和 JMeter 请求结果共同证明。

## 二、本轮重测要回答的问题

本轮只需要回答以下四个问题：

1. 会员支付 Lua 轻量化和 Pipeline 改造后，`ORDER_CREATE`、`PAYMENT_ATTEMPT` 是否还会出现数秒或十几秒的 Java 端等待。
2. Redis SLOWLOG 中是否还会出现订单快照、Provider 结果或会员支付批量 Lua 长时间独占 Redis 命令执行线程。
3. 在相同的 40,000 订单、48 消费者、prefetch 20 和同机环境下，八个区段的完整收敛时间是否下降。
4. 新退款流程在边界并发下是否仍能使 `REFUND_REQUIRED` 订单、回调、权益和工作队列最终一致收敛。

本轮不再回答“每个订单的 PENDING 0～8、CLOSING 0～4 分别耗时多少”。如以后需要逐阶段诊断，应另开小规模诊断波次，不能在正式 40K 性能波次中重新打开全量明细日志。

## 三、已保留的改造前慢记录基线

旧原始日志路径为：

```text
C:\Users\damn\Desktop\ai-temperate-main\logs\membership-payment-state-machine.log
```

编写本文时该原始文件已经不在 `logs` 目录中，因此下面三条是此前从文件中明确提取并保留下来的对比锚点：

| 旧日志位置 | operation | totalMs | otherRedisMs | 含义 |
| --- | --- | ---: | ---: | --- |
| 原第 2094 行 | ORDER_CREATE | 10940.083 | 10542.682 | Java 端完整创建约 10.94 秒，其中归入其他 Redis 步骤的等待约 10.54 秒 |
| 原第 2105 行 | ORDER_CREATE | 11968.412 | 10027.624 | Java 端完整创建约 11.97 秒，其中其他 Redis 等待约 10.03 秒 |
| 原第 294 行 | ORDER_CREATE | 3513.624 | 2460.766 | Java 端完整创建约 3.51 秒，其中其他 Redis 等待约 2.46 秒 |

必须保持以下解释边界：

- `totalMs` 是 Java 端从业务调用开始到返回的完整观察时间。
- `otherRedisMs`、`redisOrderWriteMs`、`redisProviderWriteMs` 是 Java 端对应 Redis 调用的完整等待时间，包含客户端调度、连接/事件循环排队、Redis 服务端排队、命令执行和返回处理。
- Redis SLOWLOG 才是 Redis 服务端单条命令或 Lua 的执行时间。
- 上述 10～12 秒不能写成“单条 Lua 执行了 10～12 秒”。准确表述是：高并发下，Java 端观察到的 Redis 相关调用总等待达到数秒或十几秒。
- 这三条只是已确认的慢样本，不是完整的旧分布；不得据此伪造旧版平均值、P95 或 P99。

## 四、固定测试范围

沿用已经执行过的正式八区段 40,000 账号合同，不扩大为 400,000，也不混入历史 W01～W08 场景。

固定账号范围：

```text
70000000000000000 ～ 70000000000039999
```

| 顺序 | 区段 | 用户序号 | 参照边界 | 调度目标 | 数量 |
| ---: | --- | --- | --- | --- | ---: |
| 1 | E-P1 | 00000～04999 | `expiresAt` | 全部 `-1ms` | 5,000 |
| 2 | E-PR | 05000～09999 | `expiresAt` | `-1000ms～-2ms`，500 个点循环 | 5,000 |
| 3 | E-A1 | 10000～14999 | `expiresAt` | 全部 `+1ms` | 5,000 |
| 4 | E-AR | 15000～19999 | `expiresAt` | `0ms～+998ms`，500 个点循环 | 5,000 |
| 5 | H-P1 | 20000～24999 | `hardCloseAt` | 全部 `-1ms` | 5,000 |
| 6 | H-PR | 25000～29999 | `hardCloseAt` | `-1000ms～-2ms`，500 个点循环 | 5,000 |
| 7 | H-A1 | 30000～34999 | `hardCloseAt` | 全部 `+1ms` | 5,000 |
| 8 | H-AR | 35000～39999 | `hardCloseAt` | `0ms～+998ms`，500 个点循环 | 5,000 |

为保证前后结果可比较，以下运行参数必须保持不变：

| 参数 | 固定值 |
| --- | ---: |
| 应用实例 | 1 |
| Spring Profile | `loadtest-realtime` |
| 会员 PENDING 消费者 | 48 |
| 会员 CLOSING 消费者 | 48 |
| 单消费者 prefetch | 20 |
| 创建并发 | 4096 |
| HTTP 并发 | 4096 |
| 正式预检 | 120 秒 |
| 区段间稳定窗口 | 60 秒 |
| 每区段订单 | 5,000 |
| 总订单 | 40,000 |
| Provider | 本机 `LOCAL_SIMULATOR` |

正式结果必须记录新的 Run ID、源码指纹、JAR 哈希、Java 版本、Redis/RabbitMQ/PostgreSQL 版本、CPU、内存和 Docker 资源上限。任一关键参数不同，都必须标注为“非严格同参数对比”。

## 五、慢绿字配置合同

### 5.1 当前代码的实际过滤规则

`MembershipPaymentTimingRecorder.shouldLog()` 当前会在以下任一条件成立时写日志：

```text
failure != null
或 ackAction == NACK
或 totalMs >= slowThreshold
或 detailLogEnabled == true
或命中 sampleRate 抽样
```

所以本轮必须同时满足：

```text
detailLogEnabled = false
sampleRate = 0
slowThreshold = PT1S
enabled = true
```

只关闭 `detailLogEnabled` 而保留 `sampleRate=1.0` 没有用，因为它仍会输出全部绿字。

### 5.2 执行前必须完成的配置适配

当前 `application-loadtest-realtime.yml` 把 `detail-log-enabled: true` 和 `sample-rate: 1.0` 写成了固定值，不符合本轮慢日志合同。执行重测前应改为以下环境变量形式，并保持每行配置前的中文注释：

```yaml
# 正式容量波次默认关闭逐条明细，只有专门的小规模诊断波次才允许显式打开。
detail-log-enabled: ${MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED:false}
# 正式容量波次禁止抽样写入快速事件，避免日志 I/O 干扰被测链路。
sample-rate: ${MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE:0}
# 超过一秒的操作写入慢绿字，失败和 NACK 仍由记录器无条件保留。
slow-threshold: ${MEMBERSHIP_PAYMENT_TIMING_SLOW_THRESHOLD:PT1S}
```

`Start-MembershipLoadtestApplication.ps1` 启动 Java 前必须显式设置：

```powershell
$env:MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED = 'false'
$env:MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE = '0'
$env:MEMBERSHIP_PAYMENT_TIMING_SLOW_THRESHOLD = 'PT1S'
```

不能只依赖基础 `application.yml` 的默认值，因为当前 Profile 自己覆盖了这些配置。

### 5.3 慢绿字必须保留的字段

每一条慢绿字至少要保留现有结构化字段：

```text
event
runId
traceId
messageId
orderIdB64
orderRef
operation
flow
stageIndex
outcome
decision
transition
transitionOutcome
fromStatus
toStatus
currentStatus
providerStatus
queueAgeMs
scheduledDelayMs
deliveryOverdueMs
totalMs
applicationMs
redisOrderMs
redisOrderWriteMs
redisProviderWriteMs
redisTransitionMs
otherRedisMs
rabbitPublishConfirmMs
callbackClaimMs
callbackReadMs
callbackRequeueMs
callbackCompleteMs
barQueryMs
barCloseMs
barRefundMs
ackMs
ackAction
errorClass
```

本轮最重要的新增拆分字段是：

- `redisOrderWriteMs`：订单快照写入的 Java 端等待。
- `redisProviderWriteMs`：模拟 Provider 结果写入的 Java 端等待。
- `otherRedisMs`：仍未被上述明确步骤覆盖的 Redis 调用等待，不能继续把全部 Redis 写入都笼统归到这里。
- `barRefundMs`：退款 Provider 调用耗时；本轮使用本机模拟 Provider，不代表公网 BAR 时延。

### 5.4 日志过滤后的正确现象

正常情况下，新的日志文件应远小于旧文件：

- 没有慢事件、失败或 NACK 时，某个 Run ID 可以是 0 条记录，这是允许的性能结果。
- `ORDER_CREATE totalMs=800ms outcome=SUCCESS` 不应写入。
- `ORDER_CREATE totalMs=1200ms outcome=SUCCESS` 必须写入。
- `RABBIT_PENDING totalMs=20ms outcome=NACKED` 必须写入。
- 任意 `outcome=FAILED` 必须写入。

不能再要求日志中每个订单都有 PENDING 0～8 和 CLOSING 0～4，也不能把“某订单没有绿字”解释为“状态机没有运行”。在慢日志模式下，它只表示该操作没有达到慢阈值且没有失败/NACK。

## 六、现有 40K Runner 的兼容性问题

当前 `Start-MembershipMillisecondBoundarySuite.ps1` 在最终阶段仍会：

1. 调用 `New-MembershipPaymentTimingReport.ps1` 生成全量逐阶段报告。
2. 要求 timing sample 数量大于 0。
3. 要求 timing matrix 恰好包含 40,000 个订单。
4. 要求 14 个状态机阶段全部出现在绿字中。

这些门禁建立在“全量详细绿字”前提上。改为慢绿字后，业务即使完全正确也会被旧门禁判为失败。

执行正式重测前必须把最终报告阶段改为慢日志模式：

- 保留 `Wait-TimingLogQuiescence`，确保异步 Appender 已经落盘。
- 不再要求 `membership-payment-order-stage-matrix.csv` 有 40,000 行。
- 不再要求 14 个阶段全部出现。
- 新增或改造一个慢日志报告器，只按本轮 Run ID 提取慢事件、失败和 NACK。
- 慢事件数量为 0 时允许正常生成空明细和汇总，不能因 `sampleCount=0` 失败。
- PostgreSQL 40,000 行最终校验仍然是完整性硬门禁，不能因为关闭详细日志而删除。

建议新报告器输出：

```text
membership-payment-slow-events.csv
membership-payment-slow-operation-summary.csv
membership-payment-slow-top-100.csv
membership-payment-slow-report.json
membership-payment-slow-report.md
```

慢报告至少汇总：

- 本轮慢事件总数。
- 各 operation 的慢事件数。
- `ORDER_CREATE` 和 `PAYMENT_ATTEMPT` 的慢事件数与最大 `totalMs`。
- `redisOrderWriteMs`、`redisProviderWriteMs`、`otherRedisMs`、`rabbitPublishConfirmMs` 的慢样本最大值。
- FAILED 数量、NACK 数量和错误类型。
- 最慢 100 条的完整结构化字段。

报告器必须执行以下自检：

- 每条记录的 `runId` 必须与当前运行完全一致。
- 成功且 ACK 的记录必须满足 `totalMs >= 1000`。
- `totalMs < 1000` 的记录只能是 FAILED 或 NACK，否则说明详细日志或抽样仍被错误打开。
- 不允许使用旧 Run ID 的记录填充本轮结果。

## 七、统计口径：慢日志不能计算全量平均值

慢日志只选择 `totalMs >= 1000ms` 的尾部样本，存在明确的选择偏差。因此：

- 可以统计慢事件数量、慢事件比例、最大值和最慢 Top 100。
- 可以计算“慢样本内部”的中位数，但必须写成 `slowSampleP50`，不得冒充全量 P50。
- 禁止从慢日志直接计算全量平均值、P50、P95 或 P99。
- 全量 HTTP P50/P95/P99 必须来自 JMeter 的全部请求结果。
- 全量业务步骤 P50/P95/P99 只能来自 Micrometer Timer/Histogram 等不依赖逐条日志的聚合指标。
- Redis 服务端 Lua/命令耗时必须来自 Redis SLOWLOG、`INFO commandstats` 和延迟统计。

最终报告中必须把三种口径分开：

| 数据源 | 能回答的问题 | 不能回答的问题 |
| --- | --- | --- |
| 慢绿字 | 哪些 Java 端操作超过 1 秒、时间花在哪个步骤 | 全量平均值和全量百分位 |
| JMeter/Micrometer | 全量请求或业务步骤的 P50/P95/P99 | 单条 Lua 在 Redis 服务端的执行时间 |
| Redis SLOWLOG | 单条 Redis 命令/Lua 的服务端执行时间 | Java 客户端排队和完整端到端等待 |

## 八、正式执行步骤

### 8.1 执行授权与环境边界

本轮会写入测试 PostgreSQL、Redis、RabbitMQ、`loadtest-output` 和 `logs`，并会创建 40,000 个订单及 40,000 个回调。正式执行前必须再次确认只连接本机隔离测试基础设施，不得连接生产环境。

如果需要执行 Redis `SLOWLOG RESET`、清空旧测试订单或删除旧日志，必须在应用进程停止后核对精确目标，并把这些破坏性动作列入当次授权范围。

### 8.2 执行前代码和配置检查

1. 确认会员支付 Lua 优化、Pipeline 改造和退款逻辑均已进入待测 JAR。
2. 记录 Git HEAD、工作区源码指纹和 JAR SHA-256。
3. 确认 `loadtest-realtime` 使用慢日志配置，而不是 `detail=true/sample=1.0`。
4. 确认 Runner 已取消全量 40,000 timing matrix 和 14 阶段日志门禁。
5. 确认慢报告器允许 0 条慢记录。
6. 确认两个会员队列各有 48 个 active consumer，prefetch 都为 20。
7. 确认只运行一个应用实例。

### 8.3 清理或隔离日志

每轮必须使用唯一 Run ID，例如：

```text
membership-lua-refund-slow-retest-20260824-HHmmss
```

推荐为每轮使用独立日志文件：

```text
logs/membership-payment-state-machine-<runId>.log
```

如果继续使用固定文件 `logs/membership-payment-state-machine.log`，启动前必须保证应用已经停止，然后删除或移动旧文件。报告器仍必须按 Run ID 过滤，不能只依赖“文件已经清空”。

### 8.4 基础设施前置证据

执行前保存：

- PostgreSQL 固定 40,000 账号 FREE 基线和订单/回调空基线。
- Redis 会员支付 v1/v2 Key、callback ready/processing、dirty/processing 基线。
- RabbitMQ 两条业务队列和两条 DLQ 的 Ready、Unacked、consumer、prefetch。
- Redis `SLOWLOG LEN/GET`、`INFO commandstats`、`INFO latencystats` 基线。
- JVM 堆、GC、进程 CPU、系统 CPU、系统可用内存和 Docker 资源上限。

### 8.5 启动后慢日志配置验收

在发送 40K 正式流量前，必须确认实际绑定值：

```text
enabled=true
detailLogEnabled=false
sampleRate=0
slowThreshold=PT1S
runId=<本轮唯一值>
```

优先通过受控配置测试或已有配置检查手段验证，禁止为了确认配置而把完整敏感环境变量写入日志。

### 8.6 执行八个区段

按以下顺序执行，不得并行混跑区段：

```text
E-P1 -> 60s -> E-PR -> 60s -> E-A1 -> 60s -> E-AR
     -> 60s -> H-P1 -> 60s -> H-PR -> 60s -> H-A1
     -> 60s -> H-AR
```

每个区段后必须等待 Redis 工作集合和 RabbitMQ Ready/Unacked 收敛，并保存区段开始时间、回调调度时间、完成时间和完整收敛时长。

### 8.7 最终收敛与报告

八段完成后依次执行：

1. PostgreSQL 40,000 订单逐笔最终 SQL 验证。
2. Redis callback/dirty 工作集合归零验证。
3. RabbitMQ Ready、Unacked、DLQ 归零验证。
4. 等待 timing AsyncAppender 静默并完成落盘。
5. 生成慢日志报告。
6. 保存 Redis SLOWLOG、commandstats 和延迟证据。
7. 保存 JVM/GC/CPU/内存证据。
8. 生成优化前后对比结论。

## 九、退款逻辑专项验收

本轮使用本机模拟 Provider，不调用真实公网退款平台。退款专项至少验证：

- 根据服务端真实 `received_at` 与 `hardCloseAt`，应退款的回调全部为 `REFUND_REQUIRED`。
- 对应订单最终为 `CLOSED` 或合同允许的既有终态，不得停留在 PENDING_PAYMENT/CLOSING。
- 对应订单的 `entitlement_resolution=REFUND_REQUIRED`，且不得发放 GO/PLUS/PRO/MAX 权益。
- 回调 claim 在退款成功后完成并从 ready/processing 集合移除。
- Marker 与临时 Provider 事实按新逻辑清理，不得留下阻塞后续恢复的孤儿状态。
- 退款调用失败时必须保留 claim 并有界重试；不得把未完成退款的工作错误标记为完成。
- 重复投递、重复 finalize 和重复退款请求必须幂等收敛，不得重复发放权益或破坏订单终态。
- 最终 Redis callback ready/processing、dirty/processing 全部为 0，RabbitMQ DLQ 为 0。

最终报告必须分别写清：

```text
业务判定需要退款
本机模拟 Provider 确认退款
数据库/Redis/RabbitMQ 最终收敛
```

不得把本机模拟退款成功写成真实 BAR 或真实资金渠道已经完成退款。

## 十、性能判定

### 10.1 硬门禁

以下任一条件发生时，本轮不能判为 PASS：

- 40,000 订单或 40,000 回调数量不正确。
- PostgreSQL 最终逐笔验证失败。
- 存在未决 PENDING_PAYMENT/CLOSING、错误裁决或错误权益。
- Redis callback/dirty 工作集合未清空。
- RabbitMQ Ready/Unacked 最终未归零或 DLQ 非 0。
- 日志中出现未解释的 FAILED。
- 源码指纹在测试中途变化。
- 实际日志配置不是 `detail=false/sample=0/threshold=PT1S`。

NACK 不直接等于整轮失败，但必须逐类解释，并由最终状态、有限重试和 DLQ=0 证明已经正确收敛。

### 10.2 性能目标

在同机同参数前提下，目标为：

- 不再出现旧样本中的 `ORDER_CREATE totalMs` 10～12 秒级事件。
- `ORDER_CREATE` 和 `PAYMENT_ATTEMPT` 的 1 秒以上慢事件数量显著下降，理想结果为 0。
- 单次会员支付 Lua 不进入 Redis 10ms SLOWLOG；如仍进入，必须按脚本 SHA/业务操作归因。
- 八区段完整收敛时间相对旧轮下降至少 50%。
- 新的 `redisOrderWriteMs`、`redisProviderWriteMs` 能直接说明两个已优化写入步骤，不再由 `otherRedisMs` 模糊代替。

如果功能硬门禁全部通过但性能目标未达到，应写成“功能 PASS、性能目标未达到”，不能把两者混为一个结论。

## 十一、最终报告模板

最终报告至少包含以下摘要：

| 项目 | 旧轮 | 新轮 | 变化 |
| --- | ---: | ---: | ---: |
| 总订单/回调 | 40,000/40,000 | 待测 | 待测 |
| 八区段完整收敛时间 | 旧正式报告值 | 待测 | 待测 |
| ORDER_CREATE >=1s 数量 | 旧原始分布不可恢复 | 待测 | 只报告新轮事实 |
| ORDER_CREATE 最大 totalMs | 已知样本至少 11968.412ms | 待测 | 待测 |
| PAYMENT_ATTEMPT >=1s 数量 | 旧原始分布不可恢复 | 待测 | 只报告新轮事实 |
| Redis 会员支付 SLOWLOG >=10ms | 旧轮曾命中 | 待测 | 待测 |
| Redis callback/dirty 最终残留 | 0 | 待测 | 待测 |
| RabbitMQ Ready/Unacked/DLQ | 0/0/0 | 待测 | 待测 |
| REFUND_REQUIRED 正确收敛 | 旧轮通过旧逻辑 | 待测 | 按新退款合同复核 |

慢事件明细必须展示：

```text
timestamp
operation
outcome
totalMs
redisOrderWriteMs
redisProviderWriteMs
otherRedisMs
redisTransitionMs
rabbitPublishConfirmMs
barRefundMs
ackAction
errorClass
```

结论必须使用以下固定句式之一：

```text
功能与性能均 PASS。
功能 PASS，但性能目标未达到。
功能 FAIL，性能数据仅供诊断。
测试无效：环境、源码指纹或日志配置不满足合同。
```

## 十二、交接检查清单

- [ ] 已记录新的 Run ID、源码指纹和 JAR 哈希。
- [ ] 已确认只连接本机隔离测试 PostgreSQL、Redis 和 RabbitMQ。
- [ ] 已将 `detail-log-enabled` 设为 `false`。
- [ ] 已将 `sample-rate` 设为 `0`。
- [ ] 已将 `slow-threshold` 设为 `PT1S`。
- [ ] 已确认旧全量 timing matrix/14 阶段门禁不会误报失败。
- [ ] 已确认慢报告允许 0 条慢事件。
- [ ] 已确认 48×2 消费者和 prefetch 20。
- [ ] 已保存测试前 Redis SLOWLOG/commandstats 和系统资源基线。
- [ ] 已按固定顺序完成八个 5,000 订单区段。
- [ ] 已完成 PostgreSQL 40,000 行逐笔验证。
- [ ] 已完成退款逻辑专项验证。
- [ ] 已确认 Redis 工作集合归零。
- [ ] 已确认 RabbitMQ Ready/Unacked/DLQ 归零。
- [ ] 已生成慢事件、慢操作汇总和 Top 100 报告。
- [ ] 未从慢日志计算或宣称全量平均值/P95/P99。
- [ ] 已严格区分 Java 端等待时间与 Redis SLOWLOG 服务端执行时间。

## 十三、本次文档交付边界

本次只编写重测交接文档，没有修改 `application-loadtest-realtime.yml`、应用启动脚本、40K Runner 或慢报告器，也没有执行编译、测试、40K 压测、数据库写入、Redis/RabbitMQ 操作或外部服务连接。

正式重测前，需先按第五、六节实现慢日志配置和 Runner 报告适配；完成代码交付后，再按项目第二阶段安全测试规范说明具体命令、基础设施和数据写入范围，并获得当次明确批准后执行。
