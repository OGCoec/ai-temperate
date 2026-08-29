# 会员支付调度、最近已支付索引与 Hikari 96 重测交接文档

文档日期：2026-08-24。

适用项目：`C:\Users\damn\Desktop\ai-temperate-main`。

文档状态：**待执行**。代码定向测试和隔离 PostgreSQL 执行计划验证已通过；正式 40K、运行中 Hikari 观测以及改造前后业务性能复测尚未执行，因此不得提前宣称性能 PASS。

本文件取代 [上一版 256 并发 40K 交接文档](./2026-08-25-membership-payment-256-concurrency-40k-retest-handoff.md)。上一版仍是 Lua、Pipeline、退款和 256 削峰的历史依据；本文件增加调度隔离、五千条单轮容量、最近已支付索引和 Hikari 96 的专项裁决。

## 一、本轮必须回答的问题

本轮主要验证两个优化点：

1. Callback Worker 与订单刷盘 Worker 使用独立单线程调度器，并把单轮上限从 `20 × 100 = 2,000` 提高到 `50 × 100 = 5,000` 后，五千条区段是否不再被人为拆成三轮、额外增加约十秒固定调度等待。
2. `findLatestPaidOrder` 固定查询 PAID，并增加与两个等值条件及三个倒序字段匹配的部分复合索引后，PostgreSQL 是否真实使用索引且不再额外排序。

Hikari 同时固定为：

```text
maximumPoolSize = 96
minimumIdle = 8
PostgreSQL max_connections = 100
应用实例 = 1
```

Hikari 96 是用户批准的单机压测配置，不是已证明的最优值。正式报告必须独立回答：它是否出现连接获取超时、PostgreSQL 连接耗尽、CPU/上下文切换放大或延迟反弹。

本轮不得作出以下错误宣称：

- 不得把普通部分索引描述为唯一索引；同一用户、同一等级允许存在多笔历史 PAID 订单。
- 不得用 40K FREE 用户的新购链路单独证明 `findLatestPaidOrder` 的业务调用收益；NEW_PURCHASE 分支可能根本不调用该查询。
- 不得把 `maxBatchesPerRun=50` 描述为一次数据库事务处理五千条；内部仍是最多五十个百条有界批次。
- 不得把两个独立调度线程描述为 RabbitMQ 消费者线程；PENDING/CLOSING 仍各 48 个消费者。
- 不得把 Hikari 96 描述为 PostgreSQL 可以同时安全处理 96 条重 SQL，也不得描述为 HTTP 256 并发的等量映射。
- 不得把旧轮与新轮差异全部归因于某一个改动；Lua、Pipeline、Callback SQL、退款恢复、调度、索引和连接池都已经发生变化。

## 二、已实施变更事实

| 项目 | 旧状态 | 新状态 | 验证责任 |
| --- | --- | --- | --- |
| Callback 调度 | 共享默认调度器 | `membershipPaymentCallbackTaskScheduler` 独立单线程 | 线程证据、队列收敛时间 |
| OrderPersist 调度 | 共享默认调度器 | `membershipPaymentOrderPersistTaskScheduler` 独立单线程 | 线程证据、dirty 收敛时间 |
| Callback 单轮容量 | `20 × 100 = 2,000` | `50 × 100 = 5,000` | 五千条集中 backlog 验证 |
| OrderPersist 单轮容量 | `20 × 100 = 2,000` | `50 × 100 = 5,000` | 五千条集中 backlog 验证 |
| 最近 PAID 查询 | PAID 作为 JDBC 参数 | SQL 固定 `status = 2` | Mapper 契约与真实执行计划 |
| 最近 PAID 索引 | 无完整匹配索引 | `idx_membership_order_latest_paid` | 索引存在、使用次数和 EXPLAIN |
| Hikari 最大连接 | 默认 10 | 96 | 运行配置、连接等待、DB 会话峰值 |
| Hikari最小空闲 | 跟随默认行为 | 8 | 启动与空闲时 DB 会话数 |

新索引定义必须保持为：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS
    idx_membership_order_latest_paid
    ON membership_order (
        login_identity_id,
        membership_tier,
        paid_at DESC NULLS LAST,
        created_at DESC,
        id DESC
    )
    WHERE status = 2;
```

查询必须保持为：

```sql
WHERE login_identity_id = ?
  AND membership_tier = ?
  AND status = 2
ORDER BY paid_at DESC NULLS LAST, created_at DESC, id DESC
LIMIT 1
```

把 PAID 固定为字面量是有意设计：PostgreSQL 使用 JDBC 泛化预编译计划时仍能证明查询条件蕴含 `WHERE status = 2` 的部分索引谓词。

## 三、测试规模和环境合同

截图中的 `8 × 50,000 = 400,000` 只作为放大思路，本轮仍执行已经批准的：

```text
8 × 5,000 = 40,000 orders
8 × 5,000 = 40,000 callbacks
```

| 参数 | 固定值 |
| --- | ---: |
| E-P1/E-PR/E-A1/E-AR | 每段 5,000 |
| H-P1/H-PR/H-A1/H-AR | 每段 5,000 |
| 创建 HTTP 并发 | 256 |
| 回调 HTTP 并发 | 256 |
| Tomcat accept-count/max-connections/max-threads | 256/256/256 |
| Redis 逻辑写入在途上限 | 256 |
| Redis Pipeline 上限 | 128 |
| Callback/OrderPersist 批次 | 100 |
| Callback/OrderPersist 单轮批次数 | 50 |
| Hikari max/minIdle | 96/8 |
| PENDING/CLOSING 消费者 | 48/48 |
| prefetch | 20 |
| Provider | LOCAL_SIMULATOR |
| 应用实例 | 1 |

八区段规则继续使用：

| 区段 | 用户序号 | 目标时间 |
| --- | --- | --- |
| E-P1 | 00000～04999 | `expiresAt - 1ms` |
| E-PR | 05000～09999 | `expiresAt -1000ms～-2ms` 的 500 个点循环 |
| E-A1 | 10000～14999 | `expiresAt + 1ms` |
| E-AR | 15000～19999 | `expiresAt +0ms～+998ms` 的 500 个点循环 |
| H-P1 | 20000～24999 | `hardCloseAt - 1ms` |
| H-PR | 25000～29999 | `hardCloseAt -1000ms～-2ms` 的 500 个点循环 |
| H-A1 | 30000～34999 | `hardCloseAt + 1ms` |
| H-AR | 35000～39999 | `hardCloseAt +0ms～+998ms` 的 500 个点循环 |

最终 APPLIED/REFUND_REQUIRED 仍以服务端实际 `received_at` 为准，不以计划区段名称代替裁决。

## 四、执行前代码与配置门禁

### 4.1 定向回归测试

在项目根目录执行：

```powershell
mvn -pl ai-temperate-web -am `
  "-Dtest=PaymentCallbackFlushSchedulerTest,MembershipOrderPersistSchedulerTest,MembershipPaymentPropertiesBindingTest,MembershipPaymentPersistenceContractTest,MembershipPaymentMapperIntegrationTest,MembershipOrderServiceImplTest,MembershipPlanOfferServiceImplTest,MembershipPaymentConfigurationContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

当前代码基线证据：8 个目标测试类共 43 个测试已经通过。正式打包前必须重新运行，预期仍为 0 failures、0 errors。

这些测试分别证明：

- 两个 `@Scheduled` 明确绑定不同调度器。
- 两个调度器是不同的单线程 `ThreadPoolTaskScheduler`。
- Callback 和 OrderPersist 默认均为 50 批。
- Mapper 固定 PAID 查询，不再接收 `paidStatus` 参数。
- 10,000 条同用户/同等级 PAID 历史订单下返回最新订单。
- 隔离 PostgreSQL 的 EXPLAIN 使用新索引且不包含显式 Sort。
- Hikari YAML 默认值为 96/8，并保持环境变量覆盖。

### 4.2 本机 PostgreSQL 配置和索引门禁

```powershell
$env:PGPASSWORD = $env:POSTGRES_PASSWORD

psql -X -h 127.0.0.1 -p 5431 -U postgres -d ai_temperate -v ON_ERROR_STOP=1 `
  -c "SHOW max_connections;" `
  -c "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='membership_order' AND indexname='idx_membership_order_latest_paid';"
```

预期：

```text
max_connections = 100
idx_membership_order_latest_paid 恰好存在一条
```

当前索引已经应用到本机 5431。正式运行前仍需重复检查，禁止只根据迁移文件存在就宣称数据库已生效。

### 4.3 Hikari 96 风险门禁

正式运行必须满足：

- 只启动一个应用实例。
- 关闭不必要的 DataGrip 查询窗口、测试 JVM 和其他长期 PostgreSQL 客户端。
- 不设置第二个覆盖变量把 Hikari 扩大到 96 以上。
- `POSTGRES_POOL_MAXIMUM_SIZE` 为空或等于 96。
- `POSTGRES_POOL_MINIMUM_IDLE` 为空或等于 8。
- PostgreSQL 启动后保留管理和证据采集能力；如已无法建立新的管理连接，本轮立即停止。

任何以下情况出现时，Hikari 96 判定为不合适：

- `Connection is not available, request timed out`。
- `too many clients` 或 `remaining connection slots`。
- PostgreSQL 总连接数达到 100，导致证据采集或恢复连接失败。
- 数据库 CPU、上下文切换或 SQL P99 相对较小连接池明显恶化。
- 功能能够完成，但连接等待和 DB 尾延迟比上一轮更高。

发生上述情况时不得继续扩大连接池；回退候选依次为 64、48、32，并重新执行同参数对照。

## 五、专项一：调度与五千条单轮收敛验证

### 5.1 启动后确认专用线程

从启动脚本记录取得应用 PID：

```powershell
$appPid = <启动输出中的应用PID>
$runRoot = ".\loadtest-output\soak\$runId\millisecond-boundary"

jcmd $appPid Thread.print |
  Set-Content -LiteralPath (Join-Path $runRoot 'membership-payment-thread-dump.txt') `
    -Encoding UTF8

Select-String `
  -Path (Join-Path $runRoot 'membership-payment-thread-dump.txt') `
  -Pattern 'membership-payment-callback-|membership-payment-order-persist-'
```

必须同时找到两种线程名前缀。只能找到一个或完全找不到时，调度配置未生效，本轮无效。

### 5.2 五千条 backlog 验证原则

正式 40K 每段为五千条，但回调可能边到达边处理，因此不能仅凭“该段有五千条”宣称单轮容量已验证。必须保存每段期间 Redis 工作集合采样：

```text
sampledAt
segment
callbackReadySize
callbackProcessingSize
dirtySize
dirtyProcessingSize
rabbitPaymentReady
rabbitPaymentUnacked
rabbitClosingReady
rabbitClosingUnacked
```

建议采样间隔 250～500ms，输出：

```text
loadtest-output/soak/<runId>/millisecond-boundary/scheduler-queue-samples.csv
```

裁决规则：

1. 当 callback ready+processing 或 dirty+processing 曾达到至少 2,001 时，必须继续观察其是否在同一次连续工作窗口内穿过旧的 2,000 条边界。
2. backlog 持续存在时，不得再出现处理约 2,000 条后固定停顿约五秒、再处理下一批的旧阶梯。
3. 五千条集中 backlog 应在一个最多 50 批的有界轮次内被 claim；单批失败时允许提前退出并重试，但必须留下 FAILED/NACK/重入队证据。
4. 最终 callback ready/processing、dirty/processing 全部为 0。
5. 不允许为了证明速度而删除 claim、跳过退款或关闭失败重试。

### 5.3 回调时效性指标

按区段统计：

```text
callbackResolutionMs = resolved_at - received_at
```

必须输出平均值、P50、P95、P99、最大值和以下桶数量：

```text
< 1s
1s～5s
5s～8s
8s～10s
>= 10s
```

因为调度周期仍为五秒，单条回调可能天然等待接近五秒。新调度优化的主要目标不是把所有记录变成毫秒级，而是消除旧 `2,000 + 等5秒 + 2,000 + 等5秒 + 1,000` 造成的十几秒阶梯。

专项调度性能目标：

- `callbackResolutionMs >= 10s` 为 0。
- 最大值目标低于 8s；超过 8s 的记录必须逐条关联 `dbMs`、Redis 等待、Provider refund 和重试原因。
- 每个五千条区段从最后一条回调到达至四个 Redis 工作集合归零的时间必须保存。
- 不再出现仅由 `maxBatchesPerRun=20` 造成的约十秒附加等待。

如果功能收敛但上述目标未达到，结论写为“功能 PASS，但调度性能目标未达到”。

## 六、专项二：最近已支付订单索引验证

### 6.1 为什么不能只看 40K

固定 40K 用户在运行前恢复为 FREE，创建订单时主要走 NEW_PURCHASE；该分支可以不读取最近 PAID 订单。因此：

- 40K 可以验证数据库总体负载和索引没有引入回归。
- 40K 不能单独证明 `findLatestPaidOrder` 的业务调用次数或收益。
- 索引必须通过隔离集成测试、直接 EXPLAIN 和至少一个升级业务样本单独验证。

### 6.2 记录索引使用基线

正式测试前执行并保存：

```powershell
psql -X -h 127.0.0.1 -p 5431 -U postgres -d ai_temperate -P pager=off `
  -c "SELECT clock_timestamp() AS captured_at, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch FROM pg_stat_user_indexes WHERE relname='membership_order' AND indexrelname='idx_membership_order_latest_paid';" |
  Set-Content '.\loadtest-output\latest-paid-index-before.txt' -Encoding UTF8
```

### 6.3 EXPLAIN 验证

40K 完成并执行 `ANALYZE membership_order` 后，从本轮 PAID 订单选择一个真实 `login_identity_id + membership_tier`，执行与 Mapper 等价的：

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT
    id, login_identity_id, membership_tier, pay_amount_yuan, pay_type,
    status, idempotency_key, provider_trade_no, payment_started_at, expires_at,
    closing_deadline_at, paid_at, entitlement_resolution,
    entitlement_resolved_at, state_version, created_at, updated_at
FROM membership_order
WHERE login_identity_id = :sample_login_identity_id
  AND membership_tier = :sample_membership_tier
  AND status = 2
ORDER BY paid_at DESC NULLS LAST, created_at DESC, id DESC
LIMIT 1;
```

把实际常量替换进 SQL 后保存 JSON 计划：

```text
loadtest-output/soak/<runId>/millisecond-boundary/latest-paid-order-explain.json
```

硬门禁：

- 出现 `Index Scan` 或 `Index Only Scan`。
- `Index Name` 为 `idx_membership_order_latest_paid`。
- `membership_order` 不出现 `Seq Scan`。
- 不出现独立 `Sort` 节点。
- 返回订单确实是该用户和等级下按 `paid_at/created_at/id` 排序的第一条。

如果正式数据分布过小导致优化器合理选择 Seq Scan，不能使用 `SET enable_seqscan=off` 冒充真实命中；必须使用已经存在的 10,000 条隔离集成测试证据，或准备有界专用升级样本后重新 ANALYZE。

### 6.4 记录索引使用结果

完成 EXPLAIN 和专用升级查询后再次保存：

```powershell
psql -X -h 127.0.0.1 -p 5431 -U postgres -d ai_temperate -P pager=off `
  -c "SELECT clock_timestamp() AS captured_at, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch FROM pg_stat_user_indexes WHERE relname='membership_order' AND indexrelname='idx_membership_order_latest_paid';" |
  Set-Content '.\loadtest-output\latest-paid-index-after.txt' -Encoding UTF8
```

`idx_scan` 必须相对基线增加。只证明索引存在但 `idx_scan` 没有变化时，索引专项不能判为 PASS。

## 七、Hikari 96 运行证据

测试期间每 500ms～1s 采集 PostgreSQL 会话数，至少包含：

```sql
SELECT
    clock_timestamp() AS sampled_at,
    COUNT(*) AS total_connections,
    COUNT(*) FILTER (WHERE state = 'active') AS active_connections,
    COUNT(*) FILTER (WHERE wait_event_type IS NOT NULL) AS waiting_connections
FROM pg_stat_activity
WHERE datname = current_database();
```

保存为：

```text
loadtest-output/soak/<runId>/millisecond-boundary/postgres-connection-samples.csv
```

同时保存 Micrometer/Hikari 指标（如果当前受控 Actuator 已暴露）：

```text
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_timeout_total
hikaricp_connections_acquire_seconds
hikaricp_connections_usage_seconds
```

Hikari 专项目标：

- timeout 总数为 0。
- pending 不持续增长。
- PostgreSQL 总连接数始终低于 100，并能保留管理连接。
- 连接获取 P99 不成为 `dbMs` 的主要组成部分。
- Callback Worker 和 OrderPersist Worker 不因等待连接而重新出现十秒级尾延迟。

如果 96 能完成测试但造成 DB 尾延迟上升，结论必须写明“96 可运行但不是最优连接池大小”，并安排 64/48/32 同参数 A/B，而不是继续扩大到 256 或 512。

## 八、正式 40K 执行步骤

### 8.1 构建和冻结待测产物

完成专项测试后执行：

```powershell
mvn -pl ai-temperate-web -am "-DskipTests" package

git rev-parse HEAD
java -version
Get-FileHash `
  '.\ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar' `
  -Algorithm SHA256
```

记录 Git HEAD、工作区源码指纹、JAR SHA-256、Java 版本。测试期间任何一项变化，本轮无效。

### 8.2 启动唯一应用实例

```powershell
$runId = 'membership-payment-scheduler-index-hikari96-' + `
  (Get-Date -Format 'yyyyMMdd-HHmmss')

.\loadtest\scripts\Start-MembershipLoadtestApplication.ps1 `
  -Port 6655 `
  -RunId $runId `
  -EnableMillisecondBoundary
```

启动时必须确认：

```text
SPRING_PROFILES_ACTIVE=loadtest-realtime
MEMBERSHIP_PAYMENT_DEFAULT_PROVIDER=LOCAL_SIMULATOR
POSTGRES_POOL_MAXIMUM_SIZE=96 或未设置并使用 YAML 默认 96
POSTGRES_POOL_MINIMUM_IDLE=8 或未设置并使用 YAML 默认 8
MEMBERSHIP_PAYMENT_CALLBACK_MAX_BATCHES_PER_RUN=50
MEMBERSHIP_PAYMENT_ORDER_PERSIST_MAX_BATCHES_PER_RUN=50
```

### 8.3 执行正式 Suite

第二个 PowerShell 终端使用完全相同的 `$runId`：

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

执行前再次确认并批准副作用：精确复位旧清单、本机 PostgreSQL/Redis/RabbitMQ 写入、Redis SLOWLOG RESET、创建 40K 订单与回调，以及保留正式数据和证据。禁止连接生产环境或真实 BAR。

## 九、日志与统计合同

正式 Profile 继续使用：

```text
ORDER_CREATE、PAYMENT_ATTEMPT 全量记录
其他 operation 仅记录 totalMs >= 1000ms、FAILED 或 NACK
```

因此：

- 两个 HTTP 主操作可以计算平均值、P50、P95、P99、最大值。
- Callback/PENDING/CLOSING/OrderPersist 的筛选日志只能用于慢事件与失败诊断，不能计算全量百分位。
- 正常快速状态机没有绿字，不代表没有执行。
- Java `dbMs` 是调用端完整等待；PostgreSQL EXPLAIN 的 Actual Total Time 才是该 SQL 的服务端执行证据。
- Java Redis 等待与 Redis SLOWLOG 服务端执行时间继续分开解释。

专用日志：

```text
logs/membership-payment-state-machine.log
```

每轮必须使用唯一 Run ID 和正式开始时间过滤，不得混入预热、旧轮或其他应用实例。

## 十、功能硬门禁

- 40,000 个唯一订单和 40,000 个唯一回调。
- PENDING_PAYMENT/CLOSING 未决订单为 0。
- APPLIED/REFUND_REQUIRED 与实际 `received_at` 一致。
- 权益发放、NOT_GRANTED 和退款事实一致。
- Callback ready/processing、dirty/processing 全部为 0。
- RabbitMQ Ready、Unacked、DLQ 全部为 0。
- 未解释 FAILED 为 0；NACK 必须证明重试收敛。
- 两个专用调度线程均存在。
- 不发生 Hikari 获取连接超时或 PostgreSQL 连接耗尽。
- 测试期间源码指纹、JAR 哈希和 Run ID 不变。

## 十一、性能与专项验收表

最终报告必须填写：

| 指标 | 上一轮 | 新轮 | 裁决 |
| --- | ---: | ---: | --- |
| Callback max batches/run | 20 | 50 | 配置是否生效 |
| OrderPersist max batches/run | 20 | 50 | 配置是否生效 |
| 独立支付调度线程 | 无 | 2 | 线程转储证明 |
| `callbackResolutionMs` P50/P95/P99/max | 待提取 | 待测 | 是否消除十秒阶梯 |
| Callback >=10s 数量 | 待提取 | 目标 0 | 调度硬目标 |
| 五千条区段 callback 收敛时间 | 待提取 | 待测 | 同口径比较 |
| 五千条区段 dirty 收敛时间 | 待提取 | 待测 | 同口径比较 |
| 最近 PAID 执行计划 | 旧索引+排序风险 | 新索引、无 Sort | 索引专项 |
| 新索引 `idx_scan` 增量 | 不适用 | 待测 | 必须增加 |
| Hikari max/minIdle | 10/默认 | 96/8 | 仅说明配置变化 |
| Hikari acquire P95/P99/max | 待提取 | 待测 | 不得恶化 DB 尾延迟 |
| Hikari timeout | 待提取 | 目标 0 | 硬门禁 |
| PostgreSQL 会话峰值 | 待提取 | 必须 <100 | 硬门禁 |
| ORDER_CREATE P50/P95/P99/max | 旧报告 | 待测 | 综合对比 |
| PAYMENT_ATTEMPT P50/P95/P99/max | 旧报告 | 待测 | 综合对比 |
| Redis SLOWLOG >=10ms | 旧报告 | 待测 | 按命令归因 |

不能再使用“整轮墙钟缩短 50%”作为硬指标，因为固定 PENDING 五分钟和 CLOSING 五分钟仍主导总时长。

## 十二、最终证据路径

正式运行目录：

```text
loadtest-output/soak/<runId>/millisecond-boundary/
```

除上一版已有证据外，本轮新增必须保存：

```text
membership-payment-thread-dump.txt
scheduler-queue-samples.csv
callback-resolution-latency.csv
callback-resolution-latency-summary.json
latest-paid-index-before.txt
latest-paid-order-explain.json
latest-paid-index-after.txt
postgres-connection-samples.csv
hikari-metrics-baseline.json
hikari-metrics-final.json
```

仍需保留：

```text
run-manifest.json
soak-state.json
verdict.json
scenario-orders-all.csv
callback-dispatch-all.csv
final-postgres-verification.txt
final-timestamp-evidence.csv
redis-performance-baseline.json
redis-performance-final.json
application-performance-baseline.json
application-performance-final.json
rabbitmq-final-queues.json
rabbitmq-final-consumers.json
```

## 十三、最终结论规则

总体结论只能使用：

```text
功能与性能均 PASS。
功能 PASS，但性能目标未达到。
功能 FAIL，性能数据仅供诊断。
测试无效：配置、源码指纹或环境不符合合同。
```

并必须附加两个专项结论：

```text
调度专项 PASS / FAIL / 证据不足。
最近 PAID 索引专项 PASS / FAIL / 证据不足。
Hikari 96：可接受 / 可运行但非最优 / 不可接受。
```

如果 40K 功能 PASS，但索引没有被专用升级样本执行，不得把索引专项写为 PASS，只能写“证据不足”。

## 十四、执行清单

- [ ] 已确认本轮为 8×5,000=40K，不是 400K。
- [ ] 已重新运行 43 个定向测试并全部通过。
- [ ] 已确认本机索引存在且定义匹配。
- [ ] 已确认 PostgreSQL max_connections=100。
- [ ] 已确认只有一个应用实例。
- [ ] 已确认 Hikari 96/8，且理解其接近数据库硬上限。
- [ ] 已关闭不必要的数据库客户端。
- [ ] 已记录 Git HEAD、源码指纹、JAR SHA-256 和 Java 版本。
- [ ] 已使用唯一 Run ID 启动应用和 Runner。
- [ ] 已保存两个专用调度线程的线程转储。
- [ ] 已持续采集四个 Redis 工作集合和 Rabbit 队列。
- [ ] 已证明 backlog 穿过旧的 2,000 条边界时没有固定五秒停顿。
- [ ] 已生成 callbackResolutionMs 全量分布。
- [ ] 已确认 callbackResolutionMs >=10s 数量为 0，或逐条解释失败原因。
- [ ] 已保存索引使用前后的 pg_stat_user_indexes。
- [ ] 已保存真实 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`。
- [ ] 已确认执行计划命中新索引且无 Seq Scan/Sort。
- [ ] 已采集 PostgreSQL 会话和 Hikari 指标。
- [ ] 已确认没有 Hikari timeout 和数据库连接耗尽。
- [ ] 已完成 40K 最终业务、Redis 和 RabbitMQ 收敛验证。
- [ ] 已区分调度、索引、Hikari 和其他历史优化的贡献边界。
- [ ] 已使用固定总体结论和三个专项结论。

## 十五、交付边界

截至本文生成时：

- 调度、索引和 Hikari 配置代码已经修改。
- 8 个目标测试类共 43 个测试已通过。
- 10,000 条隔离 PAID 数据的真实 PostgreSQL 执行计划已证明索引命中且无显式 Sort。
- 新索引已应用到本机 5431。
- 正式 40K、运行中 Hikari 96 观测、调度队列时间序列和正式索引统计尚未执行。

后续执行人必须先完成配置门禁、打包和指纹冻结，再启动正式 40K；任何门禁失败都不得把本轮写成性能 PASS。
