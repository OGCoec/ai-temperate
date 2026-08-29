# 会员订单双预热与 40K/80K 重测交接计划

文档日期：2026-08-26。

适用项目：`C:\Users\damn\Desktop\ai-temperate-main`。

来源任务：`codex://threads/01a03621-cb70-7ff2-b125-29815a168cd8`。

文档状态：**八区段逐段双预热编排已实现，外部合同测试已通过，正式重测尚未开始**。

本文件是下一轮唯一执行依据。它锁定旧成功案例为黄金基线，要求每个区段固定执行两次真实同规模预热，并按 `E-P1 Canary -> 40K -> 精确清理 -> 80K` 的顺序运行。

本次已交付外部 PowerShell/Groovy 编排、黄金对照报告器和合同测试；执行代理没有启动、停止或重启 PostgreSQL。用户随后于 2026-08-26 13:11:30 America/Chicago 手工重新启动了固定 5431 实例。本次只执行了不连接本机服务的 PowerShell/Groovy 合同测试和 PowerShell 静态解析；应用、Redis、RabbitMQ、JMeter 实际负载、Maven 编译/打包、安全扫描和正式外部服务验证均未执行。

---

## 一、当前接管状态

### 1.1 当前没有活动压测

2026-08-26 13:11:30 America/Chicago 之后的只读进程与端口快照显示：

- 没有命令行包含 `ai-temperate-web-0.0.1-SNAPSHOT.jar` 的 Java 进程。
- 本机 5431 已由用户手工重启并监听 `127.0.0.1:5431`；当前 Postmaster PID 为 `38460`。
- 最近一次相关 Runner 已停止，记录的四个 PID 均已退出。
- 因此不存在可以继续观察的活动压测 Run，也不得把正在运行的 PostgreSQL 误认为压测已经开始。

最近一次相关 Run：

| 项目 | 当前事实 |
| --- | --- |
| Run ID | `membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048` |
| 最终阶段 | `STOPPED` |
| 历史总状态 | `FAIL` |
| 历史停止原因 | 旧门禁判定 `PREHEAT_INSUFFICIENT` |
| Orchestrator PID | `59016`，已退出 |
| Application PID | `28104`，已退出 |
| Sampler PID | `45516`，已退出 |
| Suite PID | `24944`，已退出 |
| 证据目录 | `C:\Users\damn\Desktop\ai-temperate-main\loadtest-output\soak\membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048\millisecond-boundary` |

下一轮必须生成新的唯一 Master Run ID 和子 Run ID，禁止复用或覆盖上述目录。

### 1.2 历史 PostgreSQL 状态与当前门禁

黄金 Run 的历史 120 秒门禁证据为：

| 项目 | 历史证据 |
| --- | --- |
| verdict | `PASS` |
| 观察时长 | `120` 秒 |
| 5431 Listener/Postmaster PID | `47024` |
| Postmaster 启动时间 | `2026-08-26 11:06:10.143178-05` |
| 观察区间 | `2026-08-26T16:31:26.1863070Z` 到 `2026-08-26T16:33:27.9921004Z` |
| 新增崩溃特征 | `0` |
| 长连接采样 | `postgres-stability-watch.csv`，stderr 为空 |
| 数据目录 | `C:/Users/damn/Desktop/postgresql/data` |
| `max_connections` | `384` |

这只能证明历史 Run 启动前的 5431 稳定，不能证明当前环境稳定。

当前 Windows 进程事实：

| 项目 | 当前事实 |
| --- | --- |
| 5431 监听 | `127.0.0.1:5431`，已监听 |
| Postmaster PID | `38460` |
| Postmaster 启动时间 | `2026-08-26 13:11:30 America/Chicago` |
| 数据目录 | `C:/Users/damn/Desktop/postgresql/data` |
| 直接宿主 | PID `44584`，`cmd.exe /C postgres.exe ...` |
| Windows 服务 | `postgresql-x64-18-5431` 为 `Disabled / Stopped`，当前实例不是由该服务托管 |
| 当前 120 秒门禁 | 尚未执行 |

当前实例是用户已经启动好的外部基础设施。后续编排禁止再次启动、停止或重启 PostgreSQL，也不得关闭 PID `44584` 所在的宿主窗口/进程树；只允许读取并锁定 PID `38460`。正式运行前仍须获得用户授权并对这个现有实例执行一次新的 120 秒门禁。若 PID、启动时间、监听或长连接发生变化，必须判为“测试无效”并停止，不能自动重启后续跑。端口 5430 和其他 PostgreSQL 实例不在范围内，禁止停止、重启或修改。

### 1.3 Windows 异常退出诊断

现有日志能区分正常停库和异常终止：

- 2026-08-26 09:46:43 的受控切换包含 `接收到快速停止请求 -> 正在关闭 -> 数据库系统已关闭`，这是正常 `fast shutdown`。
- 随后的两个实例分别在 10:50:38 和 11:55:10 停止写日志，末行只是正常 checkpoint，没有 shutdown、PANIC、Postmaster 自报崩溃或完整关闭记录。
- 下一次启动分别报告“数据库系统中断/没有正确关闭/自动恢复”，而陈旧 `postmaster.pid` 仍保留 `ready`。这证明进程没有走 PostgreSQL 正常关闭路径。
- 同一时段 Windows Application Error/WER 中没有 `postgres.exe` 应用崩溃记录；固定 Windows 服务又是 `Disabled / Stopped`。因此当前高置信结论是 PostgreSQL 进程树被外部 Windows 宿主直接终止，最可能是临时 PowerShell/Codex/终端所属 Job Object 或宿主窗口结束时的进程树回收，而不是 SQL、Hikari 或 PostgreSQL 内核自行崩溃。由于历史进程审计未启用，无法仅凭现存日志锁定是哪一个旧宿主执行了终止。

本轮的处置不是自动重启，而是把用户当前手工启动的 Postmaster 当作外部进程：记录 Windows 父进程证据，同时以 Postmaster PID、启动时间、长连接和两处日志增量联合门禁。一旦变化，结果只能是“测试无效”。

---

## 二、锁定黄金基线

### 2.1 黄金样本

黄金基线固定为：

```text
Run ID:
membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048

阶段：
E-P1 预热②

唯一 HTTP 201：
5,000

min(receivedAtEpochMicros)：
1787762582645541

max(completedAtEpochMicros)：
1787762587555387

完整服务端 HTTP 墙钟：
4.909846 秒

完整 HTTP QPS：
1,018.362
```

计算口径固定为：

```text
wallClockMicros
= max(completedAtEpochMicros) - min(receivedAtEpochMicros)

QPS
= 唯一且 committed=true 的 HTTP 201 数量 * 1,000,000 / wallClockMicros
```

这份证据证明同一台机器、同一类完整 HTTP 创建链路曾经达到：

```text
5,000 条完整 HTTP 创建 < 5 秒
完整 HTTP QPS > 1,000
```

禁止用数据库 `created_at` 局部跨度、末尾 500 条峰值、JMeter 估算或客户端开始/结束时间替代该口径。所有门禁计算使用未四舍五入原值，报告展示值才允许四舍五入。

### 2.2 历史总裁决与黄金样本不是同一个概念

该历史 Run 的根 `verdict.json` 保持原样为 `FAIL`，原因是旧规则要求末两个 500 条窗口的 QPS 差异不超过 10%。预热②的旧 `verdict.json` 中：

- 5,000 个 HTTP 201 完整存在。
- 功能 `functional-verdict.json` 为 `PASS`。
- 支付与创建重叠。
- 队列连续三次采样归零。
- 完整 5K 墙钟为 4.909846 秒，QPS 为 1,018.362。
- 末两窗为 1,013.290 和 1,148.248 QPS，差异比率 11.7534%，因此旧尾窗规则写成 `FAIL`。

下一轮不得改写历史 JSON 或声称旧 Run 整体 PASS；只把其中已经成立的 E-P1 预热②完整 HTTP 样本作为黄金性能基线。末两窗及其 Redis/Rabbit P95 继续用于诊断，但不再覆盖完整 5K 已达到合同门槛的事实。

### 2.3 黄金基线分层耗时

以下数据来自黄金预热②的 5,000 个 `ORDER_CREATE` 全量聚焦事件：

| 指标 | P50 ms | P95 ms | P99 ms |
| --- | ---: | ---: | ---: |
| `ORDER_CREATE totalMs` | 150.485 | 293.444 | 378.326 |
| Redis 写排队 `redisWriteQueueWaitMs` | 40.221 | 142.899 | 202.945 |
| Redis Pipeline 执行等待 `redisPipelineExecuteMs` | 63.921 | 120.872 | 159.847 |
| Rabbit 发布总等待 `rabbitPublishConfirmMs` | 27.684 | 79.089 | 153.855 |
| Rabbit Confirm 等待 `rabbitConfirmWaitMs` | 18.655 | 54.832 | 81.012 |
| PostgreSQL 事务 `dbTransactionMs` | 1.284 | 3.622 | 11.407 |

Redis Pipeline 执行等待表示 Java 调用方观察到的 Pipeline 执行与返回等待，不得描述为单条 Lua 的纯 Redis 服务端执行时间。Rabbit 发布总等待和 Confirm 等待不得重复相加。

### 2.4 黄金基线前后半段

按 `receivedAtEpochMicros` 排序后，把 5,000 条精确分为前 2,500 条和后 2,500 条，并分别使用各自的 `min(received)` 到 `max(completed)`：

| 范围 | 数量 | 墙钟 | QPS |
| --- | ---: | ---: | ---: |
| 前半段 | 2,500 | 3.002962 秒 | 832.511 |
| 后半段 | 2,500 | 2.103774 秒 | 1,188.341 |
| 后半段相对前半段 | — | — | `+355.830 QPS / +42.742%` |

前后半段只用于判断吞吐是否随预热、排队或资源竞争发生漂移，不能替代完整区段门禁。

### 2.5 黄金证据索引

黄金证据根目录：

```text
C:\Users\damn\Desktop\ai-temperate-main\loadtest-output\soak\membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048\millisecond-boundary
```

关键文件：

| 证据 | 相对黄金根目录的路径 |
| --- | --- |
| 固定运行合同与 JAR 哈希 | `run-manifest.json`、`formal-preflight.json` |
| PostgreSQL 120 秒门禁 | `postgres-stability-gate.json`、`postgres-stability-watch.csv` |
| 黄金原始服务端 HTTP 事件 | `raw-membership-order-create-http-events.log`，报告器复核预热②恰好 5,000 个唯一且 `committed=true` 的 HTTP 201 |
| 已验证 HTTP 数量及完整边界 | `E-P1/warmup/attempt-2/verdict.json`、`E-P1/warmup/attempt-2/stability-windows.csv` |
| 预热②清单 | `E-P1/warmup/attempt-2/scenario-orders.csv` |
| 预热②功能结论 | `E-P1/warmup/attempt-2/functional-verdict.json` |
| 预热②旧尾窗结论 | `E-P1/warmup/attempt-2/verdict.json` |
| 500 条诊断窗口 | `E-P1/warmup/attempt-2/stability-windows.csv` |
| ORDER_CREATE 分层汇总 | `E-P1/warmup/attempt-2/membership-payment-focused-operation-summary.csv` |
| 精确清理回执 | `E-P1/warmup/attempt-2/warmup-reset-receipt.json`、`redis-reset-receipt.json` |

---

## 三、冻结 JAR 与配置合同

### 3.1 唯一测试 JAR

```text
C:\Users\damn\Desktop\ai-temperate-main\ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar
```

固定 SHA-256：

```text
624F4ACEE57D2E1168D07A4774927396DC2AFC012DA7B1528BB47E2AD203F73E
```

该哈希同时存在于黄金 Run 的 `run-manifest.json`、`formal-preflight.json`、`application-start.json` 和 Runner launch receipt 中。

禁止：

- 修改 Java、Lua、XML Mapper 或业务 YAML。
- 修改公开 API、SQL、索引、消息结构、状态机或 RabbitMQ 可靠性配置。
- 重新打包 JAR，或用另一个 JAR 冒充固定样本。
- 在运行开始后修改 JAR、外部编排或报告脚本。
- Git 整体回退、覆盖或清理用户现有工作树修改。

本轮只允许修改外部 PowerShell/Groovy 压测编排、合同测试和报告生成逻辑。由于 JAR 不变，不运行 Maven package。

### 3.2 固定容量

| 配置 | 固定值 |
| --- | ---: |
| Redis Pipeline 单批上限 | 64 |
| Redis lane 数量 | 6 |
| Redis 总逻辑在途 | 384 |
| 创建并发 | 256 |
| 支付发起并发 | 56 |
| 共享 HTTP 许可 | 256 |
| 回调并发 | 256 |
| Tomcat threads/connections/accept-count | 256 / 320 / 256 |
| Hikari maximum/minimum-idle | 256 / 8 |
| PostgreSQL 端口 | 5431 |
| PostgreSQL `max_connections` | 384 |

每个子 Run 的 `formal-preflight.json`、`application-start.json`、`suite-child-configuration.json` 和 `run-manifest.json` 必须四方一致。任何一处不是上述值，结论固定为“测试无效”，不得进入预热。

---

## 四、正式执行前的 5431 门禁

### 4.1 启动前检查

只允许操作：

```text
端口：5431
数据目录：C:/Users/damn/Desktop/postgresql/data
目标 max_connections：384
```

启动前必须确认：

1. 5431 对应的数据目录和配置文件路径正确。
2. 5430 及其他实例不在操作范围。
3. 没有未知测试写入者或长事务；Navicat 只读观察连接最多 8 个。
4. 固定 80K Identity、Profile、Quota 完整，测试订单与回调处于可精确复位状态。
5. Redis 会员工作集合和 RabbitMQ Ready/Unacked/DLQ 满足空基线。

### 4.2 120 秒稳定性门禁

门禁只在整轮正式测试开始前执行一次，不在预热①、预热②或区段之间重复。必须同时满足：

- 5431 在 120 秒内持续监听。
- `SHOW data_directory` 指向固定目录。
- `SHOW max_connections` 返回 384。
- Listener/Postmaster PID 在起止采样中完全一致。
- 门禁 JSON 记录 Postmaster 命令行、父 PID/父进程、Windows 服务状态，明确当前实例的 Windows 宿主边界；编排不接管该宿主。
- 独立长生命周期 `psql` 采样连接不中断。
- PostgreSQL 日志从门禁开始偏移量之后没有新增异常退出、崩溃恢复或 Postmaster 重启特征。Windows 手工启动的实际重定向日志 `C:/Users/damn/Desktop/postgresql/postgresql-5431.log` 与 `data/log` 必须同时纳入增量扫描，并在门禁 JSON 的 `monitoredLogPaths` 中留证。
- 采样器自身无 stderr、无丢样。

5431 任一次断连、PID 变化、数据目录漂移或新增异常退出，整轮固定为：

```text
合同门槛：测试无效
黄金基线复现：不裁决
```

不得把无效环境产生的低 QPS 记录为性能回退。

---

## 五、外部编排实现状态

当前脚本已落实以下编排合同：

1. `Invoke-SegmentSameScaleWarmup` 固定执行两次预热，仅预热②决定正式段放行。
2. 预热报告改用完整区段 HTTP 墙钟/QPS；末两个 500 条窗口及其 Redis/Rabbit P95 只作诊断。
3. 主编排固定为 `E-P1_CANARY_5K -> PERFORMANCE_40K -> CAPACITY_80K`。
4. 子进程桥接已传递 Canary、动态正式区段数、黄金证据和前一正式清单。
5. Canary 校验 1 行正式 QPS，40K/80K 各校验 8 行，不再写死为 8。
6. 最终摘要和逐段黄金报告均输出“合同门槛”与“黄金基线复现”两层结论。
7. 已删除跨 Run 从预热②开始的入口；任何恢复 Run 都固定从当前区段预热①开始。
8. 预热②清理后同时在 Suite、Wave 和 Groovy 首个正式请求前检查 10 秒截止时间，并用服务端首个正式创建事件进行事后精确复核。
9. 最终时间证据由八个已经通过各自功能裁决的 `server-time-verdict.csv` 精确合并，不依赖缺失的旧总表 SQL 文件。
10. 40K/80K 分别生成 `eight-segment-sustainability-summary.json`，汇总最低 QPS、最慢墙钟和 ORDER_CREATE、Redis、Rabbit、数据库事务的最差 P95/P99。

### 5.1 允许修改的外部文件

执行代理应只在以下外部压测范围内完成最小改动：

- 修改 `loadtest/scripts/New-MembershipWarmupStabilityReport.ps1`。
- 修改 `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`。
- 修改 `loadtest/scripts/Start-MembershipOrderCreateOptimizationRetest.ps1`。
- 修改 `loadtest/scripts/Invoke-MembershipOptimizationRunChild.ps1`。
- 修改 `loadtest/scripts/Start-MembershipSchedulerIndexHikariRetest.ps1` 和 Suite 子进程配置桥接。
- 修改 `loadtest/scripts/Start-MembershipLoadtestApplication.ps1`、Wave 参数与对应 Groovy 驱动，只用于冻结外部运行参数。
- 修改或新增会员订单 HTTP/黄金基线对照报告脚本。
- 更新对应 PowerShell 合同测试；不得让测试改写固定 JAR。

### 5.2 预热报告的目标行为

`New-MembershipWarmupStabilityReport.ps1` 必须：

- 保留 500 条窗口、末两窗差异、Redis Pipeline P95 和 Rabbit Confirm P95，标记为 `diagnosticOnly=true`。
- 新增完整区段的 `firstReceivedAtEpochMicros`、`lastCompletedAtEpochMicros`、`wallClockMicros`、`wallClockSeconds` 和 `fullHttpQps`。
- 5K 用 `wallClockSeconds <= 5.556` 且 `fullHttpQps >= 900` 裁决合同门槛。
- 10K 用 `wallClockSeconds <= 11.112` 且 `fullHttpQps >= 900` 裁决合同门槛。
- 功能完整、支付重叠、可靠性零错误和队列收敛仍是硬门禁。
- 单独输出黄金能力分类，不把 900 多 QPS 写成“黄金复现”。
- 把 ORDER_CREATE、Redis、Rabbit、数据库和前后半段相对黄金基线的差异写入结构化 JSON/CSV 和 Markdown。

### 5.3 固定双预热的目标行为

`Invoke-SegmentSameScaleWarmup` 已固定无条件执行 `attempt-1` 和 `attempt-2`：

```text
attempt-1 完整执行
-> 功能/可靠性/环境核验
-> 精确清理并恢复本段 FREE
-> attempt-2 完整执行
-> 功能/可靠性/环境核验
-> 精确清理并恢复本段 FREE
-> 仅按 attempt-2 的完整 HTTP 合同门槛决定是否进入正式段
```

预热①纯性能不足仍继续预热②；预热①即使达到 1,000 QPS 也不得提前进入正式段。任一预热发生 HTTP 数量错误、数据不一致、Redis/Rabbit 可靠性错误或 5431 环境错误，立即停止。预热②低于 900 QPS 或超过对应墙钟上限时停止，不执行正式段，也不执行第三次预热。

### 5.4 三阶段主编排

主编排必须调整为：

```text
E-P1_CANARY_5K
-> 精确清理 Canary 正式 5K 并恢复 80K 用户 FREE
-> PERFORMANCE_40K
-> 精确清理 40K 正式数据并恢复 80K 用户 FREE
-> CAPACITY_80K
```

编排固定沿用一个应用 PID：Canary 首次启动应用，40K 和 80K 显式复用 `application-start.json` 中的 PID 与固定 JAR。每个子 Run 仍使用独立 Run ID 和独立证据目录。

子进程配置应显式增加：

- `directConcurrencyCanary`。
- 期望正式区段数：Canary 为 1，40K/80K 为 8。
- 黄金基线 Run ID、证据根目录和固定指标。
- 前一子 Run 的精确 `scenario-orders-all.csv`，用于下一阶段开始前的精确复位。

---

## 六、固定双预热与精确清理合同

### 6.1 每个 5K 区段

```text
5K 真实预热①
-> 功能、可靠性、数据和环境收敛
-> 精确清理 5K，恢复本段 FREE
-> 5K 真实预热②
-> 功能、可靠性、数据和环境收敛
-> 黄金基线对照
-> 精确清理 5K，恢复本段 FREE
-> 10 秒内开始正式 5K
```

### 6.2 每个 10K 区段

```text
10K 真实预热①
-> 功能、可靠性、数据和环境收敛
-> 精确清理 10K，恢复本段 FREE
-> 10K 真实预热②
-> 功能、可靠性、数据和环境收敛
-> 黄金基线对照
-> 精确清理 10K，恢复本段 FREE
-> 10 秒内开始正式 10K
```

### 6.3 精确清理规则

每次清理必须以该次独立 Run ID 的 `scenario-orders.csv` 为唯一订单清单，并使用大小写敏感的 `StringComparer.Ordinal`：

1. 清单必须恰好有 5,000 或 10,000 行、唯一用户和唯一订单。
2. 只解析该清单对应的回调 ID 和 Redis Key；禁止扩大到用户范围、表范围或历史 Run 范围。
3. Redis 使用现有精确批量 `UNLINK` 路径；禁止 `KEYS *`、宽范围 `SCAN` 删除或逐 Key 网络 I/O。
4. PostgreSQL 在受控本地事务中先处理回调，再处理订单，并把本段用户恢复 FREE。
5. 清理后当前区段订单/回调必须为 0；前面已经完成的正式区段计数必须原样保留。
6. RabbitMQ 与 Redis 工作集合必须连续三次采样归零。
7. 清理回执必须分别保存在 `attempt-1`、`attempt-2` 或正式阶段目录中，禁止用后一轮回执覆盖前一轮。

5K 预热回执至少必须证明：

```text
deletedOrderCount = 5000
deletedCallbackCount = 5000
resetQuotaCount = 5000
currentGroupOrderCount = 0
currentGroupCallbackCount = 0
retainedFormalOrderCount = 预期已完成正式数量
retainedFormalCallbackCount = 预期已完成正式数量
```

10K 同理，三个本段数量必须为 10,000。

Canary 完成后，把 Canary 的 `scenario-orders-all.csv` 传给 40K 子 Run 精确清理。40K 完成后，把 40K 的 `scenario-orders-all.csv` 传给 80K 子 Run精确清理。任何清单数量、集合或影响行数不一致都立即停止，不得尝试宽范围补删。

阶段间清理必须生成 `previous-exact-reset-receipt.json`。进入 40K 时其中 `deletedOrderCount/deletedCallbackCount/resetQuotaCount` 必须均为 5,000；进入 80K 时必须均为 40,000；清理后的固定测试订单、回调和 retained formal 数量必须全部为 0。Master 在接受子 Run 结论前再次校验该回执。

---

## 七、预热②分级与停止矩阵

### 7.1 5K 预热②

| 等级 | 完整墙钟 | 完整 HTTP QPS | 结论 |
| --- | ---: | ---: | --- |
| 黄金基线复现 | `< 5.000 秒` | `> 1,000` | 成功复现以前最好能力 |
| 合同达标 | `<= 5.556 秒` | `>= 900` | 可以进入正式段，但未必复现黄金基线 |
| 不达标 | 任一合同条件不满足 | 任一合同条件不满足 | 停止，不进入正式段 |

### 7.2 10K 预热②

| 等级 | 完整墙钟 | 完整 HTTP QPS | 结论 |
| --- | ---: | ---: | --- |
| 黄金能力目标 | `< 10.000 秒` | `> 1,000` | 达到按规模线性延伸的黄金能力目标 |
| 合同达标 | `<= 11.112 秒` | `>= 900` | 可以进入正式段 |
| 不达标 | 任一合同条件不满足 | 任一合同条件不满足 | 停止，不进入正式段 |

墙钟和 QPS 两项必须同时满足。边界裁决使用未四舍五入数值。

### 7.3 停止与继续规则

| 事件 | 动作 |
| --- | --- |
| 预热①纯性能不足 | 完成精确清理，继续预热② |
| 预热①黄金复现 | 完成精确清理，仍继续预热② |
| 预热②合同性能不足 | 清理后停止，不执行正式段，不补第三次 |
| 任一预热功能、可靠性、数据一致性失败 | 立即停止并保留现场 |
| 5431 断连、PID 变化、数据目录漂移 | 标记测试无效并停止 |
| 正式区段纯性能不足 | 记录性能 FAIL，功能有效时继续后续正式区段 |
| 正式区段功能、可靠性、数据一致性失败 | 立即停止 |
| 采样器失效、JAR/配置/源码指纹漂移 | 标记测试无效并停止 |

---

## 八、执行顺序

### 8.1 E-P1 Canary

新 Master Run 的第一阶段固定为：

```text
E-P1 5K 预热①
-> 精确清理
-> E-P1 5K 预热②
-> 黄金基线对照
-> 精确清理
-> 正式 E-P1 5K
```

正式 5K 硬门槛：

- 恰好 5,000 个唯一、已提交的 HTTP 201，HTTP 失败为 0。
- 完整墙钟 `<= 5.556` 秒且 QPS `>= 900`。
- 有效创建并发 `>= 200`。
- 支付发起与创建重叠。
- Redis 拒绝、许可超时、结果等待超时为 0。
- Rabbit NACK、Return、Confirm 超时为 0。
- PostgreSQL 错误为 0。
- 回调、订单终态、权益、Redis 工作集合和 RabbitMQ 队列全部收敛。

同时单独裁决：

```text
正式 5K 是否再次达到墙钟 < 5 秒且 QPS > 1,000
```

Canary 如果只是性能 FAIL、但功能和可靠性有效，仍按合同继续 40K 采集；最终必须明确写“E-P1 性能 FAIL”。Canary 功能、可靠性、数据一致性或环境失败则立即停止。

### 8.2 40K 正式测试

先精确清理 Canary 正式 5K，再确认 80K 用户全部 FREE。随后固定执行八个 5K 区段：

```text
E-P1 -> E-PR -> E-A1 -> E-AR -> H-P1 -> H-PR -> H-A1 -> H-AR
```

每个区段都是：

```text
5K 预热① -> 清理 -> 5K 预热② -> 清理 -> 10 秒内正式 5K
```

每个正式区段使用 5K 门槛。纯性能不足继续收集其余区段；功能、可靠性、数据一致性或环境失败立即停止。

40K 阶段结束必须证明：

- `scenario-orders-all.csv` 恰好 40,000 行、40,000 个唯一订单和用户。
- 回调总数 40,000，订单终态和权益全部正确。
- 未决订单、Redis 工作集合、Rabbit Ready/Unacked/DLQ 为 0。
- 八个区段各自的正式 HTTP、分层耗时、前后半段和黄金对照证据完整。

### 8.3 清理 40K 并进入 80K

80K 前只允许使用 40K `scenario-orders-all.csv` 精确清理：

- 删除 40,000 个正式订单和对应 40,000 个回调。
- 精确清理其关联 Redis 状态。
- 把固定 80K 用户全部恢复 FREE。
- 确认 PostgreSQL 订单/回调为 0，Redis/Rabbit 为空基线。
- 保留 40K 全部证据，不删除或覆盖其运行目录。

### 8.4 80K 正式测试

固定执行同样八个区段，每段 10,000 条：

```text
10K 预热① -> 清理 -> 10K 预热② -> 清理 -> 10 秒内正式 10K
```

每个正式区段合同门槛为：

```text
完整墙钟 <= 11.112 秒
完整 HTTP QPS >= 900
```

黄金能力目标为：

```text
完整墙钟 < 10 秒
完整 HTTP QPS > 1,000
```

正式区段纯性能不足继续后续区段；功能、可靠性、数据一致性或环境失败立即停止。

### 8.5 总写入规模提示

如果三阶段全部完成，实际创建调用量为：

```text
Canary：5K + 5K + 5K = 15K
40K：8 * (5K + 5K + 5K) = 120K
80K：8 * (10K + 10K + 10K) = 240K
总创建调用：375K
```

预热和前置正式数据会按本文件精确清理；最终 80K 正式数据是否保留，必须在最终报告中明确记录，禁止默认为已经清理。

---

## 九、每轮黄金对照报告

预热①、预热②和每个正式区段都必须生成同口径对照。至少输出：

| 项目 | 黄金基线 | 当前值 | 当前减黄金 | 相对变化 |
| --- | ---: | ---: | ---: | ---: |
| 唯一 HTTP 201 | 5,000 | 待测 | 待测 | 待测 |
| 完整墙钟秒 | 4.909846 | 待测 | 待测 | 待测 |
| 完整 HTTP QPS | 1,018.362 | 待测 | 待测 | 待测 |
| ORDER_CREATE P50 ms | 150.485 | 待测 | 待测 | 待测 |
| ORDER_CREATE P95 ms | 293.444 | 待测 | 待测 | 待测 |
| ORDER_CREATE P99 ms | 378.326 | 待测 | 待测 | 待测 |
| Redis 排队 P50/P95/P99 ms | 40.221/142.899/202.945 | 待测 | 待测 | 待测 |
| Pipeline 执行 P50/P95/P99 ms | 63.921/120.872/159.847 | 待测 | 待测 | 待测 |
| Rabbit 发布总等待 P50/P95/P99 ms | 27.684/79.089/153.855 | 待测 | 待测 | 待测 |
| Rabbit Confirm 等待 P50/P95/P99 ms | 18.655/54.832/81.012 | 待测 | 待测 | 待测 |
| DB 事务 P50/P95/P99 ms | 1.284/3.622/11.407 | 待测 | 待测 | 待测 |
| 前半段 QPS | 832.511 | 待测 | 待测 | 待测 |
| 后半段 QPS | 1,188.341 | 待测 | 待测 | 待测 |
| 后半段相对前半段 | +42.742% | 待测 | 待测 | 待测 |

变化公式固定为：

```text
绝对变化 = 当前值 - 黄金值
相对变化 = (当前值 / 黄金值 - 1) * 100%
```

对墙钟和延迟，负值代表改善；对 QPS，正值代表改善。报告必须同时给绝对值和百分比，禁止只写“更快”或“更慢”。

每轮还必须列出：

- 有效创建并发及是否达到 200。
- HTTP 状态分布、重复 Trace、清单外事件和 committed=false 数量。
- Redis `inflight + availablePermits = 384`，批次 `<=64`，lane 只能为 0～5。
- Rabbit Publish Submit、Confirm Wait、NACK、Return 和 Confirm Timeout。
- Hikari active/pending/timeout，PostgreSQL 事务错误和连接采样。
- Redis/Rabbit 每 500ms 队列采样，以及数据库、Hikari、Tomcat、主机每秒采样。
- 末两个 500 条窗口及 Redis/Rabbit P95，但标题必须注明“诊断，不参与完整区段放行”。
- 第二次预热清理完成到首个正式请求的间隔，必须在 0～10 秒内。

10K 对比 5K 黄金样本时必须同时给出绝对 QPS/延迟和“规模不同”的说明；不得把 10K 墙钟直接与 4.909846 秒做等规模优劣结论。

---

## 十、心跳与断线后接管

### 10.1 心跳文件

Master 根目录应包含：

```text
loadtest-output/soak/<masterRunId>/heartbeat.json
loadtest-output/soak/<masterRunId>/run-state.json
loadtest-output/soak/<masterRunId>/run-ledger.json
```

每个子 Run 根目录应包含：

```text
loadtest-output/soak/<childRunId>/millisecond-boundary/heartbeat.json
loadtest-output/soak/<childRunId>/millisecond-boundary/run-state.json
loadtest-output/soak/<childRunId>/millisecond-boundary/soak-state.json
```

心跳每 2 秒更新，至少记录 Master/child Run ID、阶段、区段、预热轮次、Orchestrator PID、Application PID、Sampler PID、Suite PID 和采样时间。

### 10.2 断线后的读取顺序

Codex 会话断开后，先只读检查：

```powershell
Get-Content -Raw '.\loadtest-output\soak\<masterRunId>\heartbeat.json'
Get-Content -Raw '.\loadtest-output\soak\<masterRunId>\run-state.json'
Get-Content -Raw '.\loadtest-output\soak\<masterRunId>\run-ledger.json'
Get-Content -Raw '.\loadtest-output\soak\<childRunId>\millisecond-boundary\heartbeat.json'
Get-Content -Raw '.\loadtest-output\soak\<childRunId>\millisecond-boundary\soak-state.json'
```

接管规则：

1. 心跳在 10 秒内更新且 PID/命令行匹配时，只继续观察，禁止启动第二套编排。
2. 心跳超过 10 秒时先检查四个 PID 和 stdout/stderr；进程仍活着时禁止假定已死或重复启动。
3. 进程全部退出后，读取 `orchestrator-failure.json`、最后一个区段的功能结论、清理回执和 PostgreSQL 门禁证据。
4. 只允许从“完整正式区段已通过且边界清理/保留计数完全有证据”的下一区段继续。
5. 在预热中断、清理回执缺失、5431 PID 改变或采样器失效时，本 Run 停止；精确清理后必须用新 Run ID 重新执行该区段的预热①和预热②，不能把补跑算作第三次预热。
6. 任何恢复都必须继续使用固定 JAR 哈希和固定容量；无法证明时按测试无效处理。

---

## 十一、最终结论模板

最终报告必须同时给出两层结论。

第一层只允许：

```text
合同门槛：PASS
合同门槛：性能FAIL
合同门槛：功能FAIL
合同门槛：测试无效
```

第二层只允许：

```text
黄金基线复现：已复现（<5秒且>1,000 QPS）
黄金基线复现：未复现但合同达标（>=900 QPS）
黄金基线复现：未复现且不达标
黄金基线复现：不裁决（测试无效或未执行）
```

40K 和 80K 总结还必须区分：

- 全部正式区段合同 PASS。
- 功能 PASS，但一个或多个正式区段性能 FAIL。
- 功能/可靠性/数据一致性 FAIL，后续未执行。
- 环境无效，性能数据仅供诊断。

第三层八段持续性只允许：

```text
八段持续性：全部持续达标
八段持续性：部分区段性能回退
八段持续性：未完成
```

禁止把 900～1,000 QPS 描述为与黄金案例完全一样，也禁止因为旧 Run 的尾窗规则写过 FAIL 就忽略已经存在的 `<5 秒、>1,000 QPS` 完整 HTTP 成功样本。

---

## 十二、实施与验证边界

外部编排改动和离线合同测试已经完成。5431 已由用户手工启动，不代表已经授权执行门禁或负载；连接 5431 执行 120 秒门禁或发起真实 5K/10K 负载仍必须由用户另行明确授权。

第二阶段建议按以下顺序单独申请授权：

1. 已完成：只运行外部编排和报告合同测试，不连接本机服务。
2. 待授权：确认用户启动的固定 5431 当前 PID；不重启它，直接运行一次 120 秒稳定性门禁。
3. 启动固定 JAR，执行 E-P1 Canary。
4. Canary 功能有效后，自动继续 40K 和 80K；这一步会产生本文件第 8.5 节说明的本机测试写入。

不得把一次合同测试授权扩展为真实负载授权，也不得在没有新证据时宣称构建成功、功能通过、性能达标或黄金基线已经复现。

### 12.1 本次已执行的离线验证

- 4 个修改脚本及 4 个关键合同测试均通过 PowerShell AST 静态解析。
- 固定双预热、同规模预热、5K/10K 八区段边界、黄金对照、HTTP 报告、精确 Redis 清理、清理模式、Master 顺序、子进程桥接、采样器、心跳和最终裁决合同测试全部退出码为 0。
- 未执行 Maven、JAR 重建、5431 连接、应用启动或任何真实创建请求。
