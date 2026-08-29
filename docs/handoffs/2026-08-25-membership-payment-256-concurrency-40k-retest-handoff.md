# 会员支付 256 并发 40K 重测交接文档

> 历史状态：本文件已被 [调度、最近已支付索引与 Hikari 96 重测交接文档](./2026-08-24-membership-payment-scheduler-index-hikari96-40k-retest-handoff.md) 取代。本文保留用于追溯 Lua、Pipeline、256 削峰和退款改造的上一阶段合同，不得单独作为下一轮正式执行依据。

文档日期：2026-08-25。

适用项目：`C:\Users\damn\Desktop\ai-temperate-main`。

文档状态：**前置精确复位已完成，正式 40K 待执行**。2026-08-24 已精确清理中止轮 35K 订单、回调和关联 Redis 状态，并把固定 40K 用户恢复 FREE；这不代表新一轮 40K 性能测试已经执行或通过。

本文件取代 [2026-08-24 旧重测交接文档](./2026-08-24-membership-payment-lua-refund-slow-log-retest-handoff.md)。旧文件仅保留为历史资料，不得继续使用其中的 4096 并发、旧日志过滤规则或旧性能门禁。

## 一、重测目的与结论边界

本轮用于验证会员支付订单快照 `putAndGet`、128 条 Pipeline、256 条逻辑写入削峰、基础设施预热、Lua 轻量化和退款收敛逻辑在本机 40K 场景下的功能正确性与性能表现。

本轮必须回答：

1. `ORDER_CREATE`、`PAYMENT_ATTEMPT` 是否仍出现数秒或十几秒的 Java 端等待。
2. `redisOrderWriteMs`、`redisProviderWriteMs` 是否已经从 `otherRedisMs` 中准确拆分，尾延迟是否下降。
3. Redis SLOWLOG 中是否仍有会员支付 Lua 或命令超过 10ms。
4. 在 HTTP/Tomcat/Redis 逻辑写入均限制为 256 后，40,000 笔订单是否无拒绝、无丢失并最终收敛。
5. PENDING、CLOSING、Provider notify 和 REFUND_REQUIRED 是否继续遵守 Marker、Provider 查询/关单、退款和幂等规则。

本轮不能作出以下宣称：

- 不能把 Redis 逻辑在途上限 256 描述为 Redis、Lettuce 或 JDBC 有 256 条物理连接。
- 不能把 Pipeline 描述为事务、强一致或 Exactly Once。
- 不能把 Java 端数秒等待直接描述为单条 Lua 在 Redis 服务端执行了数秒。
- 不能把新轮与旧轮的差异全部归因于 Lua。旧轮 HTTP 并发为 4096，新轮为 256，新结果是 Lua、Pipeline、预热和削峰的综合效果。
- 不能把本机 `LOCAL_SIMULATOR` 的查询、关单或退款结果描述为真实 BAR 或真实资金渠道结果。
- 不能把固定 PENDING 五分钟和 CLOSING 五分钟造成的墙钟时间用于要求“整轮缩短 50%”。

## 二、正式测试合同

### 2.1 数据规模

本轮只执行 `8 × 5,000 = 40,000`，不执行截图中 `8 × 50,000 = 400,000` 的放大方案。

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

上述偏移是调度目标，不是 Windows、JVM、HTTP 或线程调度的零漂移保证。最终 `APPLIED/REFUND_REQUIRED` 裁决必须使用服务端实际 `received_at` 与 `hardCloseAt` 比较，不能只根据区段名称判断。

### 2.2 并发、Pipeline 与基础设施边界

| 参数 | 新轮固定值 | 准确含义 |
| --- | ---: | --- |
| 创建 HTTP 并发 | 256 | JMeter 创建请求的公平并发上限 |
| 回调 HTTP 并发 | 256 | JMeter 回调请求的公平并发上限 |
| Tomcat `accept-count` | 256 | 受控 Profile 的连接接收队列上限 |
| Tomcat `max-connections` | 256 | 受控 Profile 的同时连接上限 |
| Tomcat `threads.max` | 256 | 受控 Profile 的工作线程上限 |
| Redis 逻辑写入在途上限 | 256 | 已取得许可且 Future 尚未完成的订单写入数，包含排队和正在执行的 Pipeline |
| Redis Pipeline 单批上限 | 128 | 每批最多 128 条独立单订单 `putAndGet` Lua，不提供批次事务 |
| Redis Pipeline 聚合窗口 | 1ms | 第一条进入批次后的最长微批等待窗口 |
| Redis 写入提交超时 | 30s | 取得许可和等待结果共用的总截止时间 |
| RabbitMQ Channel 缓存 | 256 | Publisher Channel 复用与背压边界 |
| RabbitMQ `requested-channel-max` | 512 | 覆盖固定消费者、Publisher Channel 和管理/预热余量 |
| PENDING 消费者 | 48 | 不随 HTTP 并发调整为 256 |
| CLOSING 消费者 | 48 | 不随 HTTP 并发调整为 256 |
| 单消费者 prefetch | 20 | 两条会员业务队列保持一致 |
| 应用实例 | 1 | 禁止第二个实例重复消费或污染统计 |
| Provider | `LOCAL_SIMULATOR` | 不连接真实 BAR |
| Spring Profile | `loadtest-realtime` | 端口固定为 6655 |
| 正式业务预热 | 8 个订单 | 正式计时前创建、发起支付、取消并精确清理 |
| 正式预检 | 120s | 吸收预热残余并检查环境稳定 |
| 区段间稳定窗口 | 60s | 每段后等待队列和工作集合收敛 |

必须明确：项目没有把 Lettuce 物理连接池或 Hikari/JDBC 连接池从 4096 改成 256。`maximum-inflight=256` 是应用层 Redis 写入 bulkhead；RabbitMQ 业务消费者仍为 `48 + 48`。

### 2.3 时间与业务事实

固定业务时间关系：

```text
hardCloseAt = expiresAt + 5 minutes
```

最终裁决必须同时满足现有业务事实：

```text
payment_started_at < expires_at
paid_at >= payment_started_at
paid_at <= received_at
received_at < hardCloseAt  -> APPLIED
received_at >= hardCloseAt -> REFUND_REQUIRED
```

`received_at` 在 `expiresAt` 之后但在 `hardCloseAt` 之前仍属于软关闭窗口内的合法支付，不得仅因越过 `expiresAt` 判定退款。

## 三、状态机验证矩阵

正式 40K 不重新打开 PENDING/CLOSING 全量逐阶段绿字。阶段逻辑通过执行前单元/集成测试、40K 最终数据库事实、Redis 工作集合、RabbitMQ 队列/DLQ 和慢失败诊断共同证明。

| 触发场景 | 查订单 | 查 Marker | Marker 不存在时的 Provider 行为 | 预期状态/动作 | 正式证据 |
| --- | --- | --- | --- | --- | --- |
| 前端 GET 订单 | 是，Redis miss 时回源 DB | 否 | 无 | 返回 Redis 当前赢家或 DB 事实 | HTTP/JUnit、最终订单事实 |
| PENDING 0～7 | 是 | 是 | 不调用 Provider | Marker 不存在时只发布下一阶段 | Consumer 测试、Rabbit 收敛、慢失败诊断 |
| PENDING 8 | 是 | 是 | `queryPayment` | 安全状态下从 PENDING_PAYMENT 迁移到 CLOSING，并发布 CLOSING 0 | Consumer/Lua 测试、最终状态 |
| CLOSING 0～3 | 是 | 是 | 不调用 Provider | Marker 不存在时只发布下一阶段 | Rabbit 收敛、慢失败诊断 |
| CLOSING 4 | 是 | 是 | `closePayment`；返回 PAID 时再 `queryPayment` | 安全关闭后迁移 CLOSED；不安全或 UNKNOWN 时受控失败/重试 | 最终状态、Provider 测试、DLQ |
| Provider notify | 校验订单绑定 | 成功通知写 Marker | 立即权威 `queryPayment` | 根据服务端事实执行 APPLIED 或 REFUND_REQUIRED | Callback 测试、数据库回调事实 |
| REFUND_REQUIRED | 是 | 必须精确匹配 callbackId | 调用本机模拟退款 | 成功后完成 claim、清 Marker；失败时保留工作并有界重试 | Callback/退款测试、Redis 工作集合、最终权益 |

以下不变量必须在交接结论中逐项确认：

- Marker 存在时，PENDING/CLOSING 时间链停止续发和外部查询/关单，把收敛权交给 Callback Worker。
- PENDING 8 只有在 Marker 不存在且 Provider 结果允许时才进入 CLOSING。
- CLOSING 4 的前置检查与 Lua 终态迁移之间若出现 Marker，仍不得抢占 Callback Worker。
- REFUND_REQUIRED 不得发放 GO/PLUS/PRO/MAX 权益。
- 重复通知、重复 finalize 和重试必须幂等收敛。
- 未完成退款的 claim 不得被错误标记完成。

## 四、日志与耗时统计合同

### 4.1 实际日志选择规则

正式 Profile 固定：

```text
enabled = true
detailLogEnabled = false
sampleRate = 0
slowThreshold = PT1S
forceLogOperations = ORDER_CREATE,PAYMENT_ATTEMPT
```

因此：

- `ORDER_CREATE`、`PAYMENT_ATTEMPT` 无论快慢都全量写入专用计时日志。
- 其他 operation 只在 `totalMs >= 1000ms`、FAILED 或 NACK 时写入。
- 正常快速 PENDING/CLOSING 没有绿字，不代表状态机没有调用。
- 白名单外出现快速成功记录表示日志配置被错误放大，应将本轮判为测试无效。
- RabbitMQ 重投即使低于一秒也必须写诊断记录，通过 `deliveryCount > 0` 与首次投递区分。

日志正文使用版本化紧凑格式 `v=2`。短键只压缩存储，不改变报告列名；报告器同时兼容旧长格式并把短键展开为长列。快速 HTTP 主操作省略无意义的 `none/unavailable/0.000` 字段，慢、失败、NACK 和重投才附加 trace、message、flow、stage、队列等待与 BAR 诊断字段。Logback 专用文件只输出 `%msg%n`，异步队列仍设置为不丢弃并允许反压。

本轮日志体积合同：

```text
旧单轮约 563 MiB
新轮目标 19～28 MiB
中心目标约 24 MiB（约旧单轮 1/23）
```

诊断事件过多导致超限时禁止删除异常；应在报告中写明“日志体积目标未达到”。

专用日志路径：

```text
logs/membership-payment-state-machine.log
```

每轮必须使用唯一 Run ID 和 `formalStartedAtEpochMs` 双重过滤。只清空文件不能替代 Run ID 过滤，也不能让预热事件混入正式统计。

### 4.2 可以计算的统计

`ORDER_CREATE`、`PAYMENT_ATTEMPT` 是全量样本，可以从聚焦报告计算：

- attemptCount、uniqueOrderCount、SUCCESS/FAILED/NACKED。
- 平均值、P50、P95、P99、最大 `totalMs`。
- `redisOrderMs`、`redisOrderWriteMs`、`redisProviderWriteMs`、`otherRedisMs`、`rabbitPublishConfirmMs` 的分布。

白名单外 operation 是筛选后的诊断样本，只能统计：

- 慢事件数、FAILED 数、NACK 数。
- 最慢 Top 100 和单条耗时拆分。
- 慢样本内部统计，且必须明确标注为慢样本。

禁止用白名单外慢绿字计算或宣称该 operation 的全量平均值、P50、P95 或 P99。

### 4.3 三种时间口径必须分离

| 数据源 | 表示的时间 | 不表示的时间 |
| --- | --- | --- |
| 应用结构化绿字 | Java 端从调用开始到返回的完整观察时间，包括客户端排队、事件循环、Redis 排队、执行和响应处理 | 单条 Lua 纯服务端执行时间 |
| Redis SLOWLOG | Redis 服务端执行一条命令或 Lua 的时间 | Java/Lettuce 等待和完整端到端时间 |
| JMeter/Micrometer | HTTP 或业务步骤全量分布 | Redis 单条 Lua 的服务端时间 |

改造前只保留了三条已确认 `ORDER_CREATE` 慢样本：

| 旧样本 | `totalMs` | 准确用途 |
| --- | ---: | --- |
| A | 3513.624ms | 已确认单条锚点，不是旧平均值或百分位 |
| B | 10940.083ms | 已确认单条锚点，不是单条 Lua 执行时间 |
| C | 11968.412ms | 已确认单条锚点，不是旧 P99 |

## 五、执行前验证

本节所有命令属于项目第二阶段验证。执行人必须先确认只使用本机隔离环境；不得连接生产 PostgreSQL、Redis、RabbitMQ 或真实 BAR。

### 5.1 PowerShell 合同测试

```powershell
& .\loadtest\scripts\tests\Test-MembershipMillisecondBoundaryContract.ps1
& .\loadtest\scripts\tests\Test-MembershipPaymentFocusedTimingReport.ps1
```

预期：两个脚本均输出 `PASS` 并以退出码 0 结束。

### 5.2 Service 单元与状态机测试

```powershell
mvn -pl ai-temperate-service -am `
  "-Dtest=MembershipPaymentRedisWritePropertiesTest,MembershipPaymentOrderLookupServiceImplTest,MembershipOrderServiceImplTest,MembershipPaymentAttemptServiceImplTest,MembershipPaymentCheckConsumerServiceImplTest,PaymentCallbackReceiveServiceImplTest,MembershipPaymentRejectedCallbackResumeServiceImplTest,MembershipPaymentCallbackDecisionServiceImplTest,MembershipPaymentTimingRecorderTest,MembershipPaymentTimingAspectTest,MembershipOrderSnapshotWriteCoordinatorImplTest,MembershipPaymentInfrastructureWarmupServiceImplTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：编译成功，目标测试 0 failures、0 errors。

### 5.3 Redis 7.4 Testcontainers 与 Pipeline 集成测试

```powershell
mvn -pl ai-temperate-service -am `
  "-Dtest=MembershipPaymentRedisArchitectureTest,MembershipPaymentRedisIntegrationTest,MembershipPaymentPipelineIntegrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：使用临时 Redis/PostgreSQL Testcontainers，目标测试 0 failures、0 errors；不连接本机正式测试库。

### 5.4 Web、YAML、Warmup 与 Spring 上下文测试

```powershell
mvn -pl ai-temperate-web -am `
  "-Dtest=MembershipPaymentLoadtestProfileYamlTest,MembershipPaymentWarmupRunnerTest,AiTemperateApplicationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`loadtest-realtime` 的 256/128/512 合同、Warmup 失败策略和 Spring 上下文均通过。

### 5.5 构建正式待测 JAR

只有上述测试全部通过后才构建：

```powershell
mvn -pl ai-temperate-web -am "-DskipTests" package
```

必须记录：

```powershell
git rev-parse HEAD
java -version
Get-FileHash `
  '.\ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar' `
  -Algorithm SHA256
```

Runner 会再次生成包含 Git HEAD、工作区源码指纹、JAR SHA-256、Java 版本和固定参数的 `run-manifest.json`。测试期间源码指纹或 JAR 发生变化时，本轮无效。

## 六、正式执行步骤

### 6.1 已取得授权并完成的精确复位

本任务已经取得用户的明确执行授权。中止轮实际只有前七段完成，当前清理事实不是旧 40K 总清单，而是七份 `scenario-orders.csv` 的大小写敏感并集：

```text
E-P1、E-PR、E-A1、E-AR、H-P1、H-PR、H-A1
总行数/唯一订单/数据库订单/回调 = 35,000/35,000/35,000/35,000
StringComparer.Ordinal 集合差异 = 0
```

2026-08-24 已完成：

- 精确 `UNLINK` 35K 关联订单状态 Key，并验证四个工作集合无清单成员。
- 受控 Fixture 在同一事务内先删除 35K 回调，再删除 35K 订单。
- 仅把 `70000000000000000～70000000000039999` 恢复 FREE；另外 16 个额度账号保持不变。
- 最终基线为 Identity/Profile/ACTIVE/Quota/精确 FREE 均 40K，固定用户订单/回调为 0/0，会员 Rabbit Ready/Unacked/DLQ 为 0。

复位证据位于：

```text
loadtest-output/soak/membership-payment-256-40k-retest-20260824-170500/millisecond-boundary/
```

正式运行仍会产生以下副作用：

- 写入本机测试 PostgreSQL、Redis 和 RabbitMQ。
- 在预热和 120 秒预检完成后执行 Redis `SLOWLOG RESET`。
- 创建新的 40,000 个订单、40,000 个支付尝试和 40,000 个本机回调。
- 写入 `logs`、`loadtest-output` 和临时 Token 文件。
- 成功或失败后保留正式数据和证据，等待人工分析。

禁止连接生产环境、真实 BAR 或真实资金渠道。

### 6.2 第一终端：启动唯一应用实例

```powershell
$runId = 'membership-payment-256-retest-' + (Get-Date -Format 'yyyyMMdd-HHmmss')

.\loadtest\scripts\Start-MembershipLoadtestApplication.ps1 `
  -Port 6655 `
  -RunId $runId `
  -EnableMillisecondBoundary
```

必须保存启动脚本输出的 PID、JAR SHA-256、stdout、stderr 和计时日志路径。启动脚本必须确认：

```text
SPRING_PROFILES_ACTIVE=loadtest-realtime
MEMBERSHIP_PAYMENT_DEFAULT_PROVIDER=LOCAL_SIMULATOR
MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED=false
MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE=0
MEMBERSHIP_PAYMENT_TIMING_FORCE_LOG_OPERATIONS=ORDER_CREATE,PAYMENT_ATTEMPT
```

不得同时存在第二个 `AiTemperateApplication` JVM。

### 6.3 第二终端：执行 40K Suite

第二个终端必须使用与应用完全相同的 `$runId`：

```powershell
.\loadtest\scripts\Start-MembershipMillisecondBoundarySuite.ps1 `
  -RunId $runId `
  -PreviousScenarioOrdersCsvPath @(
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\E-P1\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\E-PR\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\E-A1\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\E-AR\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\H-P1\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\H-PR\scenario-orders.csv'
    '.\loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary\H-A1\scenario-orders.csv'
  ) `
  -CreationConcurrency 256 `
  -HttpConcurrency 256 `
  -WarmupOrderCount 8 `
  -PrecheckSeconds 120 `
  -InterSegmentSeconds 60
```

Base64URL 订单号大小写敏感，所有唯一性和集合比较必须使用 `StringComparer.Ordinal`。Runner 支持一份完整清单或多份已完成区段清单，不再把旧轮数量硬编码为 40K；清单行数、唯一订单、唯一用户、数据库订单和回调必须彼此一致。当前数据库已是 0/0 基线时只允许幂等续跑，不得重新扩大删除范围。

### 6.4 Runner 固定顺序

Runner 必须按下列顺序完成：

1. 检测已验证的 0/0/FREE 基线并幂等续跑；若存在残留，则只按输入清单精确复位。
2. 准备 40K 固定用户 FREE 基线。
3. 验证 PostgreSQL、Redis、RabbitMQ 空基线。
4. 用 8 个固定账号执行 `ORDER_CREATE -> PAYMENT_ATTEMPT -> CANCEL` 正式业务预热。
5. 等待预热订单终态、延迟 PENDING 消息消费并精确清理预热事实。
6. 执行 120 秒稳定预检。
7. 重置 Redis SLOWLOG，写入 `formalStartedAtEpochMs`。
8. 顺序执行 `E-P1 -> E-PR -> E-A1 -> E-AR -> H-P1 -> H-PR -> H-A1 -> H-AR`。
9. 每段之间等待 60 秒并检查 RabbitMQ、Redis 工作集合和源码指纹。
10. 执行 PostgreSQL 40,000 行最终验证。
11. 保存 Redis、JVM 和日志证据，生成聚焦耗时报告。
12. 保留全部正式数据并写入最终 verdict。

## 七、功能验收硬门禁

以下任一项不满足，功能不得判为 PASS：

- 场景清单恰好包含 40,000 个唯一用户和 40,000 个唯一订单。
- 每个订单恰好有一笔本轮回调，回调总数为 40,000。
- `PENDING_PAYMENT/CLOSING` 未决订单为 0。
- `APPLIED/REFUND_REQUIRED` 与服务端实际 `received_at`、`hardCloseAt` 一致。
- APPLIED 订单最终为 PAID，并获得目标 GO/PLUS/PRO/MAX 套餐。
- REFUND_REQUIRED 订单最终遵守退款合同，并且不得发放付费权益。
- Callback resolution、entitlement resolution 和关键时间戳完整。
- Redis callback ready、callback processing、dirty、dirty processing 全部为 0。
- RabbitMQ 两条业务队列 Ready、Unacked 均为 0，两条 DLQ 均为 0。
- 未解释的 FAILED 为 0。
- NACK 必须逐类解释，并由有限重试、最终事实和 DLQ=0 证明已经收敛。
- 不发生 Redis 写入 bulkhead 拒绝或 `submit-timeout`。
- Run ID、源码指纹、JAR SHA-256 和正式开始时间匹配，测试期间源码未变化。
- 两个主操作各覆盖 40,000 个唯一订单，聚焦报告不混入预热或旧 Run ID。

## 八、性能验收与结果表达

### 8.1 性能目标

性能判为 PASS 必须同时满足：

1. `ORDER_CREATE totalMs >= 1000ms` 数量为 0。
2. `PAYMENT_ATTEMPT totalMs >= 1000ms` 数量为 0。
3. `redisOrderWriteMs` 最大值低于 1000ms。
4. `redisProviderWriteMs` 最大值低于 1000ms。
5. 不再出现 3513.624ms、10940.083ms、11968.412ms 量级的 ORDER_CREATE 样本。
6. 正式开始时间之后，会员支付相关 Lua/命令不进入 Redis 10ms SLOWLOG。
7. Redis 写入指标中没有 `maximum-inflight=256` 的拒绝、许可超时或结果等待超时。
8. HTTP、Rabbit Confirm、Redis、JVM/GC/CPU 和区段收敛证据完整。
9. 本 Run ID 紧凑日志为 19～28 MiB，中心目标约 24 MiB；超限不丢弃异常，只降低性能结论。

如果功能全部通过但上述任一性能目标未达到，必须写成“功能 PASS，但性能目标未达到”，不能因为结果比旧慢样本好就改写门禁。

### 8.2 必须输出的性能表

对 `ORDER_CREATE`、`PAYMENT_ATTEMPT` 分别输出：

| 指标 | ORDER_CREATE | PAYMENT_ATTEMPT |
| --- | ---: | ---: |
| 样本数/唯一订单 | 待测 | 待测 |
| 平均 `totalMs` | 待测 | 待测 |
| P50/P95/P99/max `totalMs` | 待测 | 待测 |
| P50/P95/P99/max `redisOrderWriteMs` | 待测 | 待测 |
| P50/P95/P99/max `redisProviderWriteMs` | 待测 | 待测 |
| P50/P95/P99/max `rabbitPublishConfirmMs` | 待测 | 待测 |
| `totalMs >= 1s` | 待测 | 待测 |
| FAILED/NACKED | 待测 | 待测 |

还必须输出：

- Redis SLOWLOG 按脚本 SHA、命令和耗时归因。
- `INFO commandstats`、`INFO latencystats`、Redis CPU/内存前后差异。
- JVM 工作集、私有内存、线程数、CPU 秒和 GC heap 前后差异。
- RabbitMQ Confirm、Ready、Unacked、DLQ 和消费者/prefetch 证据。
- 每个区段的开始、回调调度、完成和收敛时间。
- `deliveryOverdueMs` 的慢诊断，但不从筛选日志伪造全量百分位。

由于新轮并发从 4096 降为 256，最终对比标题必须写为：

```text
旧 4096 并发方案 vs 新 256 有界方案的综合对比
```

如需单独证明 Lua 或 Pipeline 的收益，必须另建“旧代码同为 256 并发”的基线；不在本轮范围内。

## 九、证据与文件索引

正式运行根目录：

```text
loadtest-output/soak/<runId>/millisecond-boundary/
```

交接完成前必须确认以下文件存在并记录绝对路径：

| 证据 | 文件 |
| --- | --- |
| 运行合同 | `run-manifest.json` |
| 当前阶段/失败原因 | `soak-state.json` |
| 最终判定 | `verdict.json` |
| 数据保留证明 | `data-preserved.json` |
| 正式预热证据 | `formal-warmup.json` |
| PostgreSQL 最终输出 | `final-postgres-verification.txt` |
| 40K 最终时间证据 | `final-timestamp-evidence.csv` |
| 40K 场景清单 | `scenario-orders-all.csv` |
| 40K 回调调度证据 | `callback-dispatch-all.csv` |
| Redis 正式基线 | `redis-performance-baseline.json` |
| Redis 最终快照 | `redis-performance-final.json` |
| JVM 正式基线 | `application-performance-baseline.json` |
| JVM 最终快照 | `application-performance-final.json` |

固定日志目录还会生成：

```text
logs/membership-payment-state-machine.log
logs/membership-payment-focused-events.csv
logs/membership-payment-focused-operation-summary.csv
logs/membership-payment-focused-top-100.csv
logs/membership-payment-slow-failure-diagnostics.csv
logs/membership-payment-focused-report.json
logs/membership-payment-focused-report.md
```

这些报告使用固定文件名。下一轮启动前必须先把本轮文件复制到当前 `<runId>/millisecond-boundary/` 目录归档，禁止被后续运行覆盖。

RabbitMQ 最终证据必须额外保存到运行目录：

```powershell
$runRoot = ".\loadtest-output\soak\$runId\millisecond-boundary"

docker exec rabbitmq1 rabbitmqctl list_queues --formatter json `
  name consumers messages_ready messages_unacknowledged durable type |
  Set-Content -LiteralPath (Join-Path $runRoot 'rabbitmq-final-queues.json') `
    -Encoding UTF8

docker exec rabbitmq1 rabbitmqctl list_consumers --formatter json `
  queue_name prefetch_count active |
  Set-Content -LiteralPath (Join-Path $runRoot 'rabbitmq-final-consumers.json') `
    -Encoding UTF8
```

执行这些命令时不得输出 RabbitMQ 密码、Redis 密码、Token 或其他密钥。

## 十、最终结论模板

最终结论只能使用以下四种固定句式之一：

```text
功能与性能均 PASS。
功能 PASS，但性能目标未达到。
功能 FAIL，性能数据仅供诊断。
测试无效：配置、源码指纹或环境不符合合同。
```

报告摘要至少包含：

| 项目 | 旧轮事实 | 新轮结果 | 解释 |
| --- | ---: | ---: | --- |
| 并发 | 4096 | 256 | 非严格同参数对比 |
| Pipeline | 旧方案 | 128 | 单订单 Lua，Pipeline 不提供事务 |
| Redis 逻辑在途 | 无当前 256 bulkhead | 256 | 不是物理连接数 |
| 总订单/回调 | 40,000/40,000 | 待测 | 必须完整 |
| ORDER_CREATE P50/P95/P99/max | 旧完整分布不可恢复 | 待测 | 旧三条仅为锚点 |
| PAYMENT_ATTEMPT P50/P95/P99/max | 旧完整分布不可恢复 | 待测 | 只报告新轮事实 |
| ORDER_CREATE >=1s | 旧完整数量不可恢复 | 待测 | 新轮目标 0 |
| PAYMENT_ATTEMPT >=1s | 旧完整数量不可恢复 | 待测 | 新轮目标 0 |
| Redis 会员支付 SLOWLOG >=10ms | 旧轮曾命中 | 待测 | 按脚本/命令归因 |
| Redis 工作集合最终残留 | 0 | 待测 | 必须为 0 |
| Rabbit Ready/Unacked/DLQ | 0/0/0 | 待测 | 必须为 0/0/0 |
| REFUND_REQUIRED 收敛 | 旧逻辑通过 | 待测 | 按新退款合同复核 |

## 十一、交接检查清单

- [ ] 已确认本轮是 8×5,000=40,000，不是 400,000。
- [ ] 已确认创建并发、回调并发和 Tomcat 三项上限均为 256。
- [ ] 已确认 Redis `maximum-inflight=256`、Pipeline=128、flush window=1ms。
- [ ] 已确认 256 不是 Lettuce、Redis 或 JDBC 物理连接数。
- [ ] 已确认 Rabbit requested-channel-max=512、Channel 缓存=256。
- [ ] 已确认 PENDING/CLOSING 消费者仍各 48，prefetch=20。
- [ ] 已确认只有一个应用实例，Provider 为 LOCAL_SIMULATOR。
- [ ] 已取得正式执行和数据写入/重置授权。
- [ ] 已通过 PowerShell、Service、Redis Testcontainers、Web/YAML 和 Spring 上下文测试。
- [ ] 已记录 Git HEAD、源码指纹、JAR SHA-256 和 Java 版本。
- [x] 已用七段 35K 清单和 `StringComparer.Ordinal` 证明清单与数据库完全同集。
- [x] 已精确删除中止轮 35K Redis/回调/订单并把固定 40K 用户恢复 FREE。
- [x] 已确认另外 16 个非测试额度账号未修改。
- [ ] 已使用同一唯一 Run ID 启动应用和 Suite。
- [ ] 已确认 detail=false、sample=0、slow threshold=1s、force operations 为两个 HTTP 主操作。
- [ ] 已完成 8 个正式业务预热订单并在正式计时前清理。
- [ ] 已在预热和预检后重置 Redis SLOWLOG。
- [ ] 已按固定顺序完成八个 5,000 订单区段。
- [ ] 已完成 PostgreSQL 40,000 行最终验证。
- [ ] 已确认状态机、Marker、Provider query/close 和 REFUND_REQUIRED 合同。
- [ ] 已确认 Redis 工作集合归零。
- [ ] 已确认 RabbitMQ Ready、Unacked、DLQ 归零。
- [ ] 已生成两个 HTTP 主操作的平均值、P50、P95、P99 和最大值。
- [ ] 未从白名单外慢日志计算全量平均值或百分位。
- [ ] 已区分 Java 端 Redis 等待与 Redis SLOWLOG 服务端执行时间。
- [ ] 已把固定名称日志和报告复制到本轮运行目录归档。
- [ ] 已使用四种固定结论之一，且没有提前宣称性能通过。

## 十二、本文件交付边界

截至本文本次更新，前置精确复位已执行并留存证据；正式 40K JMeter 尚未启动。后续必须先完成代码验证、打包、指纹冻结、预热和 120 秒预检，任何门禁失败都不得进入八区段。
