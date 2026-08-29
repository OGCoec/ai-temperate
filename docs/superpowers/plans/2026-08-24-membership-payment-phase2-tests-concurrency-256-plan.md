# 会员支付第二阶段测试适配与 256 并发收敛计划

> **实施边界：** 本计划用于生产 Java/Lua 改动完成后的第二阶段。先修改测试代码、压测脚本和 `loadtest-realtime` 配置；修改完成后仍不自动运行 Maven、测试、应用或外部 Redis/RabbitMQ/PostgreSQL，执行验证需要用户再次明确批准。

## 一、目标与数字口径

本阶段同时完成两件事：

1. 让测试准确覆盖已经落地的 `putAndGet Lua + 128 条 Pipeline + 256 条在途写入 + 技术预热 + 慢日志` 生产逻辑。
2. 把正式毫秒边界压测的外部请求并发从 `4096` 收敛到 `256`，即原值的十六分之一，但保持每个区段 5,000 个订单、整套 40,000 个订单和原始毫秒边界分布不变。

必须区分以下四个概念：

| 边界 | 当前事实 | 本阶段决定 |
|---|---:|---:|
| JMeter 创建请求并发 | 4096 | 256 |
| JMeter 回调 HTTP 并发 | 4096 | 256 |
| Tomcat 工作线程/连接/接收队列 | 4096 | 256 |
| 会员订单 Redis 逻辑写入在途数 | 已是 256 | 保持 256 并增加测试锁定 |

`4096` 从来不是 Redis 物理连接池大小。项目没有为 Lettuce 配置 4096 连接池；Spring Data Redis 使用共享连接，新增的 `maximum-inflight=256` 限制的是“排队中 + Pipeline 执行中”的逻辑订单写入数。PostgreSQL Hikari 也没有 4096 配置，当前未显式设置 `maximum-pool-size`，不应为了统一数字把 JDBC 池错误扩到 256。

RabbitMQ 仍保留每条状态机队列 48 个消费者。发布 Channel 缓存从 512 收敛到 256，`requested-channel-max` 收敛到 512，为 96 个监听 Channel、最多 256 个发布 Channel及管理/预热留出有界余量。

## 二、首先修复现有测试与生产合同不一致

### 任务 1：订单查询回源测试改用协调器

**修改：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentOrderLookupServiceImplTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/MembershipPaymentPipelineIntegrationTest.java`

具体修改：

1. 新增 `MembershipOrderSnapshotWriteCoordinator` Mock，并按新构造器顺序注入。
2. Redis 命中和数据库终态回源用例继续断言协调器完全不被调用。
3. 非终态数据库回源用例改成：
   - `snapshotStore.find()` 第一次返回空；
   - 数据库返回版本 1；
   - `snapshotWriteCoordinator.putAndGet(databaseSnapshot)` 返回并发形成的版本 2；
   - 最终结果必须直接是版本 2；
   - 禁止再断言 `put -> find`，并显式断言回源后没有第二次 `find()`。
4. 集成测试中的真实 Lookup Service 装配一个真实协调器或受控直通协调器，不能绕回旧的 `put + find` 行为。

这组测试锁定的核心不是“少调用一个方法”，而是 Lua 返回 Redis 裁决后的当前赢家，避免高版本快照被数据库旧版本覆盖。

### 任务 2：订单创建 Service 测试改用 `putAndGet`

**修改：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/order/MembershipOrderServiceImplTest.java`

具体修改：

1. 在 `setUp()` 中创建协调器 Mock，修复两处手工构造 `MembershipOrderServiceImpl` 的参数。
2. 正常创建用例让协调器返回输入快照，并把旧断言 `snapshotStore.put(result.snapshot())` 改成 `snapshotWriteCoordinator.putAndGet(...)`。
3. 新增“Redis 已有更高版本”用例：数据库提交 PENDING 版本 1，但协调器返回 CLOSING 版本 2；Provider 初始化和下一阶段发布必须基于版本 2 当前状态，不得基于旧数据库快照继续错误调度。
4. Redis 快照直接命中的旧幂等订单、终态订单和业务拒绝路径必须断言协调器不调用。
5. 保留 PostgreSQL 微秒精度断言，确保引入微批不改变时间字段规范化。

### 任务 3：支付发起 Service 测试保留两个实时读取边界

**修改：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/order/MembershipPaymentAttemptServiceImplTest.java`

具体修改：

1. 所有测试辅助构造器新增协调器参数，避免每个用例重复手工装配。
2. 数据库 `startOrGet` 后由协调器 `putAndGet` 返回当前快照，不再模拟 `put + find`。
3. 正常链路必须严格验证顺序：

```text
初始 snapshotStore.find
-> PostgreSQL transactionService.startOrGet
-> snapshotWriteCoordinator.putAndGet
-> Provider createCheckout
-> 最终 snapshotStore.find
```

4. 最后一次 `find` 必须保留，并新增并发取消/回调用例：Provider 返回期间 Redis 变为 CLOSING、PAID 或 CANCELLED 时，对外结果必须采用最终实时状态。
5. 初始读取已经发现终态、数据库拒绝、Provider 未执行等路径，必须断言协调器和 Provider 没有多余调用。

## 三、Redis Lua 与 Store 集成测试

### 任务 4：新增 `putAndGet` 单对象原子语义测试

**修改：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisIntegrationTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/redis/MembershipPaymentRedisArchitectureTest.java`

新增用例：

1. Key 不存在时返回 `CREATED` 对应的完整 16 字段快照，写后不需要额外 `HGETALL`。
2. 新版本替换旧版本时返回新快照，并维持六小时 TTL。
3. 相同版本返回 `UNCHANGED` 当前快照，不重复重建 Hash。
4. 传入旧版本时返回 `STALE` 当前高版本快照，状态、版本和交易号均不得回退。
5. Lua 返回缺字段、非法 outcome 或订单 ID 不一致时，Java 编解码必须抛现有基础设施异常，且日志不泄露原始 Redis 值。
6. 架构测试锁定订单创建和支付发起生产源码不能重新出现 `put(...)` 后立即 `find(...)` 的旧组合。

### 任务 5：验证 Pipeline 顺序、边界和失败语义

在现有 500 条 Redis Testcontainers 用例基础上增加：

1. `putAndGetAll(500)` 必须得到 500 个结果，覆盖 3 个 128 条满批和 1 个 116 条尾批。
2. 输入顺序必须与输出顺序一致；同一订单 ID 的重复输入也不得静默去重。
3. 第 129、257、385 条等跨批边界结果必须与对应输入匹配。
4. 某个 Pipeline 返回数量不足、单项协议损坏或连接异常时抛基础设施异常；已经成功的前批次不伪装成回滚。
5. 对同一 500 条输入安全重试，最终状态只按 `stateVersion` 收敛，不发生版本倒退。

这组用例只证明 Store 层拆批和 Lua 兼容性；跨 HTTP 请求的 1 ms 聚合由下一组纯单元测试验证，避免 Testcontainers 时序抖动造成假失败。

## 四、微批协调器的确定性测试

### 任务 6：新增协调器单元测试

**新增：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/store/impl/MembershipOrderSnapshotWriteCoordinatorImplTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentRedisWritePropertiesTest.java`

协调器测试使用可控 Fake Store、`CountDownLatch` 和测试专用 Executor；禁止依赖长时间 `Thread.sleep()`。在测试中使用小边界验证机制，在配置合同测试中单独锁定生产默认值 128/1 ms/256。

必须覆盖：

1. 单条请求在 flush window 到达后提交一条 Pipeline，并按 Future 返回。
2. 128 条同时提交时只调用一次 `putAndGetAll`，批量严格为 128。
3. 129 条拆成 `128 + 1`，500 条拆成 3 个满批和 1 个尾批且顺序不变。
4. `maximumInflight` 已满时，新请求在同一个总截止时间内失败；取得许可和等待结果不能各自再等待一次 30 秒。
5. Store 抛异常时，同批全部 Future 失败、所有许可被释放，后续新批仍能提交。
6. Store 返回数量与输入数量不一致时整批失败，不能按错误索引完成其他 Future。
7. 调用线程中断时恢复 interrupt 标志；已经入队的幂等写入不被错误取消。
8. `destroy()` 先停止接收并排空；超时后所有未完成 Future 受控失败，不能遗留许可或后台工作线程。
9. 指标只使用低基数 outcome，覆盖 batch size、queue wait、inflight 增减和 rejected 原因。

属性测试覆盖全部上下边界：

- `batchSize` 为 1 和 128 合法，0 和 129 非法；硬上限固定为 128，防止环境变量重新放大；
- `flushWindow` 为 0.1 ms 和 5 ms 合法，越界非法；
- `maximumInflight` 小于 batch size 或大于 256 非法；
- 生产默认值必须是 `128/1ms/256/30s/5s`。

## 五、计时日志与慢日志测试

### 任务 7：修正 Timing Aspect 的口径

**修改：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentTimingAspectTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentTimingRecorderTest.java`

具体修改：

1. Aspect 测试新增协调器代理，断言 HTTP 线程从调用协调器到 Future 返回的完整时间计入 `redisOrderWriteMs`。
2. Store 的 `find/findAll` 只计入 `redisOrderMs`；Provider 写入只计入 `redisProviderWriteMs`。
3. Worker 线程没有 HTTP TimingContext，不能把同一 Pipeline 又记一次到请求级耗时。
4. 删除旧的 `forcedModeSuppressesUnlistedSuccessFailureAndNack` 错误合同，替换为四个独立用例：
   - 白名单内快速成功必记；
   - 白名单外快速成功且 sample=0 不记；
   - 白名单外超过 1 秒仍记；
   - 白名单外 FAILED 或 NACK 始终记。
5. 日志断言必须包含 `totalMs/redisOrderMs/redisOrderWriteMs/redisProviderWriteMs/otherRedisMs`，并继续验证不输出敏感异常内容。

### 任务 8：正式启动只强制两个 HTTP 操作，慢请求规则保持全局

**修改：**

- `loadtest/scripts/Start-MembershipLoadtestApplication.ps1`
- `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- `loadtest/scripts/New-MembershipPaymentFocusedTimingReport.ps1`
- `loadtest/scripts/tests/Test-MembershipPaymentFocusedTimingReport.ps1`
- `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

统一强制列表：

```text
ORDER_CREATE,PAYMENT_ATTEMPT
```

报告程序把记录分成两个集合：

- 性能主样本：`ORDER_CREATE`、`PAYMENT_ATTEMPT`，用于前后 P50/P95/P99 和 Redis 拆分比较；
- 异常诊断样本：其他 operation 中的慢请求、FAILED、NACK，单独统计，禁止因为不在两个操作白名单而报“日志污染”或丢弃。

因此新日志不是全量绿字，也不是只允许两个 operation 存在；准确合同是“两个 HTTP 操作全量 + 所有 operation 的慢请求/失败/NACK”。

## 六、无副作用预热测试

### 任务 9：Redis Lua 技术预热测试

**新增：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/warmup/MembershipPaymentInfrastructureWarmupServiceImplTest.java`

覆盖：

1. 先执行 PING，再按文件名排序执行全部 `lua/membership-payment/*.lua` 的 `SCRIPT LOAD`。
2. 每个脚本非空、文件名合法、返回 40 位 SHA1；重复资源、空脚本、非法 SHA 均启动失败。
3. 测试显式证明预热没有 `EVAL/EVALSHA`、没有业务 Key、没有订单、没有 Rabbit 消息副作用。

### 任务 10：Rabbit 被动声明与 fail-fast 测试

**新增：**

- `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/config/MembershipPaymentWarmupRunnerTest.java`

覆盖：

1. `enabled=false` 时 Redis 和 Rabbit 都不调用。
2. `enabled=true` 时先 Redis，后 Rabbit passive declare；只声明既有 Exchange/Queue，不 publish。
3. `failFast=true` 时任何预热异常阻止启动。
4. `failFast=false` 时只输出脱敏 warn 并继续启动。

正式业务预热不写进生产 ApplicationRunner。它由压测套件在正式计时前使用 8 个固定测试账号执行 `ORDER_CREATE + PAYMENT_ATTEMPT`，随后取消订单、等待十秒 PENDING 消息消费并调用现有精确 Reset；预检窗口确认 Redis/Rabbit/PostgreSQL 回到干净基线后，再清空 SLOWLOG 并设置 `formalStartedAtEpochMs`。报告器只接收该时间下界之后的事件，这样既预热 JIT、序列化、数据库语句和 Confirm，又不会把预热订单混入 40,000 条正式证据。

## 七、把 4096 请求并发收敛为 256

### 任务 11：修改运行时压测 Profile

**修改：**

- `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml`
- `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/loadtest/MembershipPaymentLoadtestProfileYamlTest.java`

配置改为：

```yaml
spring.rabbitmq.requested-channel-max = 512
spring.rabbitmq.cache.channel.size    = 256
server.tomcat.accept-count            = 256
server.tomcat.max-connections         = 256
server.tomcat.threads.max             = 256
app.membership-payment.redis-write.maximum-inflight = 256
```

所有 YAML 中文注释同步改成 256 合同，不能保留“四千零九十六”或“512 发布上限”等过期描述。Profile 测试锁定上述六个值、Rabbit checkout timeout、批量 128、flush 1 ms、技术预热开启和两个强制日志操作。

这里不新增 Lettuce 256 连接池，也不把 Hikari 改成 256。Redis 的 256 是逻辑写入 bulkhead；JDBC 继续使用小连接池，避免本机 PostgreSQL 被过量连接和上下文切换拖慢。

### 任务 12：修改正式 JMeter 驱动和套件参数

**修改：**

- `loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1`
- `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- `loadtest/scripts/jmeter/membership-millisecond-boundary.groovy`
- `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

具体修改：

1. `CreationConcurrency` 和 `HttpConcurrency` 默认值从 4096 改为 256。
2. PowerShell `ValidateRange` 与 Groovy 硬上限都从 4096 收紧到 256；调用者不能通过参数重新放大。
3. 仍为 5,000 个用户创建 5,000 个虚拟线程任务，但创建和回调分别由公平 Semaphore(256) 削峰；数据量和到期时间不缩小。
4. 连接重试注释和错误信息改为 256 合同，继续只重试没有获得任何 HTTP 响应的 `ConnectException`。
5. Run manifest 明确记录 `creationConcurrency=256`、`httpConcurrency=256`、`redisWriteMaximumInflight=256`，报告不能把本轮和旧 4096 轮次混为同参数对比。
6. Rabbit 运行前门禁不再要求 channel_max 跟 4096 一样大，改为至少 512；两条业务队列仍各自必须恰好 48 个消费者。
7. 合同测试同时禁止脚本重新出现默认或上限 4096。

## 八、测试执行顺序（需要再次批准）

修改代码后先停在“未执行”状态。获得运行批准后按以下顺序：

1. PowerShell 静态合同：256 并发、慢日志列表、JMeter 数据量和 Rabbit 48 消费者合同。
2. Service 配置、订单 Lookup、订单创建、支付发起、Timing Recorder/Aspect 单元测试。
3. 协调器确定性单元测试，检查进程结束后没有遗留 worker。
4. Redis Testcontainers 集成测试：`putAndGet`、500 条 Pipeline、幂等和高版本保护。
5. Web YAML、Warmup Runner 与 Spring 上下文测试。
6. 本机 32 并发小流量烟测，确认日志和清理流程。
7. 单区段 5,000 条、并发 256 的预演。
8. 八区段 40,000 条正式回归，最后采集 Redis SLOWLOG、commandstats、应用 P50/P95/P99、Rabbit Ready/Unacked/Confirm 和完整收敛时间。

## 九、验收标准

1. 所有手工装配测试使用新协调器，不再模拟已经删除的 `put -> find` 链路。
2. 单订单 Lua 保持原子并返回 Redis 当前赢家；旧版本无法覆盖新版本。
3. 单个 Pipeline 不超过 128，逻辑在途订单不超过 256。
4. 5,000 条订单和回调全部完成，数据量不因并发下降而减少。
5. JMeter、Tomcat 和 Redis 写入边界均锁定为 256，配置或参数不能静默回到 4096。
6. Rabbit 两队列继续各 48 消费者，Channel 上限能容纳监听器、发布和预热但不再申请 16,384。
7. 日志只全量记录 `ORDER_CREATE/PAYMENT_ATTEMPT`，同时保留所有 operation 的慢请求、FAILED 和 NACK。
8. 正式预热数据在计时前被精确清理，SLOWLOG 和应用日志中能明确区分 warmup 与 formal run。
9. 前后性能结论必须注明参数：旧 4096 与新 256 的比较是“算法 + 削峰”的综合收益；若要隔离算法收益，必须另做同为 256 并发的旧代码基线。
