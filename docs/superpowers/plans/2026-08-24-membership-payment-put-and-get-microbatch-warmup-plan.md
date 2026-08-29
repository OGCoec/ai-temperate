# 会员支付 Redis Put-and-Get、HTTP 微批与正式预热实施计划

> **执行约束：** 本计划只能由当前根代理在现有工作区实施，禁止委派子代理。第一阶段只修改生产 Java、Lua 和生产资源配置；交付源码后必须立即停止。修改测试代码、运行测试/编译、启动应用以及连接 Redis、RabbitMQ、PostgreSQL 都必须分别等待用户再次明确批准。

**目标：** 消除订单创建与支付发起链路中的 `put -> HGETALL` 重复往返，把并发 HTTP 单订单写入收敛为有界的 128 条 Pipeline，并通过无业务副作用的启动预热减少连接和 Lua 首次加载抖动。

**核心架构：** 每个订单继续由一条轻量 Lua 独立保证原子性；Java 协调器跨 HTTP 请求收集最多 128 个订单，等待窗口最多 1 ms，通过一个 Pipeline 提交 128 条独立 Lua。一个公平的 256 许可 bulkhead 限制“排队中 + 正在执行”的逻辑写入总数。Pipeline 只减少网络往返，不提供批次事务。PostgreSQL 仍是最终事实来源，Redis 写入失败或超时继续映射为现有受控 503，并依靠订单版本和请求幂等安全重试。

**技术栈：** Java 21、Spring Boot 3.5、Spring Data Redis/Lettuce、Redis 7.4 Lua、Spring AMQP/RabbitMQ、Micrometer。

---

## 一、已经确认的现状与修改边界

当前生产链路是：

```text
ORDER_CREATE
PostgreSQL createOrGet
  -> put_order_snapshot.lua
  -> HGETALL 当前快照
  -> Provider 初始化
  -> Rabbit Confirm

PAYMENT_ATTEMPT
HGETALL 初始实时状态
  -> PostgreSQL startOrGet
  -> put_order_snapshot.lua
  -> HGETALL 当前快照
  -> Provider createCheckout
  -> HGETALL 最终实时状态
```

修改后是：

```text
ORDER_CREATE
PostgreSQL createOrGet
  -> MembershipOrderSnapshotWriteCoordinator.putAndGet
       -> 最多等待 1 ms，最多聚合 128 条
       -> Pipeline[128 × 独立 put_and_get_order_snapshot.lua]
  -> Provider 初始化
  -> Rabbit Confirm

PAYMENT_ATTEMPT
HGETALL 初始实时状态（保留）
  -> PostgreSQL startOrGet
  -> MembershipOrderSnapshotWriteCoordinator.putAndGet
  -> Provider createCheckout
  -> HGETALL 最终实时状态（保留）
```

必须保留支付发起的最后一次 `find`：Provider 执行期间可能并发发生取消或支付回调，只有重新读取 Redis 实时状态才能避免向客户端返回已经失效的支付提交信息。

本次不做以下改动：

- 不把 128 个或 500 个订单放进同一条 Lua；单订单原子边界不变。
- 不把 Redis 命令执行线程配置成 256；Redis 服务端仍串行执行这些原子脚本。
- 不修改 PostgreSQL 事务、RabbitMQ ACK/Confirm、状态机阶段、订单 Key、Hash 字段或 TTL。
- 不在生产启动时创建测试订单、调用 Provider、发送 Rabbit 业务消息或清理业务数据。
- 不在第一阶段修改 `src/test`、JMeter、PowerShell 测试脚本或测试报告程序。

---

## 二、阶段门禁

### 阶段一：只改生产源码

允许修改：

- `ai-temperate-service/src/main/java`
- `ai-temperate-service/src/main/resources/lua`
- `ai-temperate-web/src/main/java`
- `ai-temperate-web/src/main/resources/application*.yml`
- 本计划和交接文档

阶段一完成后只交付源码差异和未验证清单，立即停止。禁止运行 Maven、测试、应用、外部 Redis/RabbitMQ/PostgreSQL 命令。

### 阶段二：用户再次批准后，才修改测试代码

补充单元测试、Redis 集成测试、Spring 上下文测试和压测脚本，但仍不自动执行。

### 阶段三：用户再次批准后，才执行验证

按“静态契约 -> 单元测试 -> Redis 集成 -> Spring 上下文 -> 小流量 -> 5,000 同时到期回归”的顺序运行。

---

## 三、阶段一生产源码实施任务

### 任务 1：新增独立配置边界

**新增：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentRedisWriteProperties.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentWarmupProperties.java`

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentConfiguration.java`
- `ai-temperate-web/src/main/resources/application.yml`
- `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml`

`MembershipPaymentRedisWriteProperties` 使用前缀 `app.membership-payment.redis-write`，固定校验：

```text
batch-size       = 128，允许 1..128
flush-window     = 1ms，允许 0.1..5ms
maximum-inflight = 256，必须 >= batch-size，最大 256
submit-timeout   = 30s，必须为正且有界
shutdown-timeout = 5s，必须为正且有界
```

`maximum-inflight` 指已经取得许可但 Future 尚未完成的逻辑订单数，包含内部队列和当前 Pipeline，不是 Redis 线程数，也不是 Lettuce 连接数。

`MembershipPaymentWarmupProperties` 使用前缀 `app.membership-payment.warmup`：

```text
enabled   = false（基础配置默认关闭）
fail-fast = true
```

`loadtest-realtime` 显式把 warmup 打开。所有新增 YAML 父节点和配置行必须遵守“一行中文注释，下一行配置”的项目规范，并继续支持环境变量覆盖。

在 `MembershipPaymentConfiguration` 的 `@EnableConfigurationProperties` 中注册两个新属性类，并新增一个专用单线程 `ExecutorService` Bean：

```java
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
@Bean(name = "membershipPaymentRedisWriteExecutor", destroyMethod = "shutdown")
ExecutorService membershipPaymentRedisWriteExecutor() {
    return Executors.newSingleThreadExecutor(
            Thread.ofPlatform()
                    .name("membership-payment-redis-write-", 0)
                    .factory());
}
```

该线程只运行微批协调器的一个长期 drain loop，不执行 HTTP、数据库或 Provider 工作。线程销毁由 Spring 管理，禁止在业务 Service 中临时创建线程池。

### 任务 2：新增原子 `putAndGet` Lua

**新增：**

- `ai-temperate-service/src/main/resources/lua/membership-payment/put_and_get_order_snapshot.lua`

脚本必须完整保留现有 `put_order_snapshot.lua` 的版本和 TTL 语义：

- Redis 版本大于传入版本：返回 `STALE`，不得覆盖，刷新当前 Key TTL。
- 版本相等：返回 `UNCHANGED`，不得覆盖，刷新当前 Key TTL。
- 传入版本更高或 Key 不存在：`UNLINK` 旧 Hash，一次多字段 `HSET` 写入固定 16 个字段，设置六小时 TTL。
- 在同一条 Lua 内通过一次固定字段 `HMGET` 返回最终当前快照。
- 脚本没有订单批量循环；仅有构造 16 个字段参数的固定小循环。

返回协议采用 Redis 多段数组，禁止使用容易受内容分隔符影响的拼接字符串：

```lua
local ORDER_FIELDS = {
    'schemaVersion', 'orderId', 'loginIdentityId', 'membershipTier',
    'payAmountYuan', 'payType', 'status', 'idempotencyKey',
    'providerTradeNo', 'paymentStartedAt', 'expiresAt',
    'closingDeadlineAt', 'paidAt', 'stateVersion', 'createdAt', 'updatedAt'
}

-- 完成版本裁决和可选写入后再读取；整个过程仍在一条 Lua 内原子执行。
local values = redis.call('HMGET', snapshot_key, unpack(ORDER_FIELDS))
local response = {outcome}
for index = 1, #values do
    response[#response + 1] = values[index] or false
end
return response
```

返回数组第 1 项是 `CREATED/REPLACED/UNCHANGED/STALE`，第 2..17 项严格对应上述 16 个字段。Java 端必须拒绝缺项、多项、非法 outcome、缺失必填字段或返回 `orderId` 与输入不一致。

保留现有 `put_order_snapshot.lua`，供不需要返回快照的后台恢复和 `putAll()` 使用；本阶段不为了代码复用而强迫后台路径接收 16 个无用返回字段。

### 任务 3：扩展 Redis Store 与固定返回编解码

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/MembershipOrderSnapshotStore.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/impl/MembershipPaymentRedisCodec.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/impl/RedisMembershipOrderSnapshotStore.java`

在 Store 接口中新增：

```java
MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot);

List<MembershipOrderSnapshot> putAndGetAll(
        List<MembershipOrderSnapshot> snapshots);
```

这里必须返回 `List`，并与输入保持一一对应和原始顺序；不能返回 Map，也不能对重复订单 ID 静默去重。并发的两个 HTTP 请求可能提交同一个订单，Redis 按 Pipeline 命令顺序分别执行后，每个 Future 都必须得到其命令执行时的当前快照。

`MembershipPaymentRedisCodec` 新增唯一的固定字段顺序常量和 `readOrderScriptReply`：

- 把 Lua 返回的 `String/byte[]/null` 安全转换为字段值。
- 校验数组长度必须为 17。
- 校验 outcome 只能是四个既有值。
- 把第 2..17 项按固定字段顺序组装后复用现有 `readOrder()`。
- 对 `providerTradeNo` 等可选值只接受空字符串，不接受缺失字段。
- 错误只抛基础设施数据损坏异常，不把 Redis 原始值写入日志。

`RedisMembershipOrderSnapshotStore` 新增 `DefaultRedisScript<List>` 类型的 `PUT_AND_GET`：

- `putAndGet()` 执行一次新 Lua，解析并校验返回订单 ID。
- `putAndGetAll()` 每 128 条建立一个 Pipeline，Pipeline 内每个订单执行独立新 Lua。
- 每批返回数量必须与提交数量完全相等；每个数组返回必须合法；输出顺序与输入一致。
- 最大公开批量仍为 500，因此直接调用 500 条时内部拆成 `128 + 128 + 128 + 116` 四个 Pipeline；协调器正常只会传入不超过 128 条。
- Pipeline 某批失败时不回滚已经完成的上一批；调用方依赖 `stateVersion` 和数据库幂等安全重试。

现有 `put()/putAll()/find()/findAll()` 保持签名和语义不变，防止扩大对持久化恢复、订单查询和回调链路的影响面。

### 任务 4：实现跨 HTTP 请求的有界微批协调器

**新增：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/MembershipOrderSnapshotWriteCoordinator.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/store/impl/MembershipOrderSnapshotWriteCoordinatorImpl.java`

接口只公开同步业务语义：

```java
public interface MembershipOrderSnapshotWriteCoordinator {
    MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot);
}
```

实现类使用：

- 一个公平 `Semaphore(256, true)` 控制全部逻辑在途写入。
- 一个 `ArrayBlockingQueue<PendingWrite>` 保存已经取得许可的请求。
- 一个 Spring 管理的单线程 Executor 运行 drain loop。
- `PendingWrite` 保存不可变快照、提交时间和 `CompletableFuture<MembershipOrderSnapshot>`。

提交过程：

```text
HTTP 线程在 submit-timeout 内取得许可
  -> 入有界队列
  -> 阻塞等待自己的 Future
  -> 成功返回当前 Redis 快照
```

取得许可和等待 Future 共用同一个单调时钟 deadline，禁止两个阶段各自重新计算 30 秒而把一次调用放大成 60 秒。

drain loop：

```text
take 第一条
  -> 从第一条进入批次开始最多等待 1 ms
  -> drain 到 128 条时立即停止等待
  -> snapshotStore.putAndGetAll(按入队顺序的快照)
  -> 按索引完成每个 Future
  -> 每个 Future 完成后释放对应许可
```

必须处理以下并发边界：

- 许可等待超时：抛 `MembershipPaymentInfrastructureException`，最终映射现有 Redis unavailable 503。
- HTTP 等待被中断：恢复线程中断标记；已经入队的 Redis 恢复操作仍允许完成，因为 PostgreSQL 事实可能已经提交。
- Pipeline 返回不完整或异常：本批所有尚未完成 Future 以同一个基础设施异常结束，逐一释放许可。
- 单个 Future 因请求超时无人接收：worker 仍完成幂等写入，不能把已提交数据库订单留成永久无缓存状态。
- 停机：先停止接受新请求，在 `shutdown-timeout` 内尽力排空；到期后让剩余 Future 受控失败，禁止永久挂住 Tomcat 线程。
- 协调器不可调用数据库、Provider 或 RabbitMQ，避免在单线程 worker 上形成跨基础设施队头阻塞。

这个协调器不是“把 256 条一起发给 Redis”。唯一 worker 每次最多向 Redis 提交当前 128 条 Pipeline；256 只是应用层有界等待总量，可容纳一个执行批和一个等待批。超出的 HTTP 请求在进入 Lettuce 之前等待，因此不会继续放大 Lettuce 命令队列。

### 任务 5：替换订单创建与支付发起的重复往返

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipOrderServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentAttemptServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipPaymentOrderLookupServiceImpl.java`

三个实现类都只注入 `MembershipOrderSnapshotWriteCoordinator` 接口，禁止依赖具体 Impl。

`MembershipOrderServiceImpl.restoreRealtimeState()` 从：

```java
snapshotStore.put(databaseSnapshot);
return snapshotStore.find(databaseSnapshot.orderId()).orElse(databaseSnapshot);
```

改为：

```java
return snapshotWriteCoordinator.putAndGet(databaseSnapshot);
```

`MembershipPaymentAttemptServiceImpl.start()` 从：

```java
snapshotStore.put(databaseSnapshot);
MembershipOrderSnapshot current = snapshotStore.find(...).orElse(databaseSnapshot);
```

改为：

```java
MembershipOrderSnapshot current =
        snapshotWriteCoordinator.putAndGet(databaseSnapshot);
```

初始 `find` 和 Provider 返回后的最终 `find` 均保留。Provider 只能在 `putAndGet` 已经返回且当前状态仍允许发起后调用。

`MembershipPaymentOrderLookupServiceImpl` 的 Redis 缺失回源也改为 `putAndGet`，避免回源路径继续保留 `put -> find`。终态数据库快照是否写入 Redis继续服从现有业务条件，不借本次优化改变 Cache-Aside 语义。

事务边界不变：协调器只能在 PostgreSQL 本地事务已经提交后调用。出现超时或 Redis 不可用时，数据库事实不会回滚；客户端重试由现有 idempotency key 和版本裁决收敛。

### 任务 6：保证计时仍覆盖真实的排队等待

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentStepTimingAspect.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentMetrics.java`

因为 Redis Pipeline 在 worker 线程执行，而现有耗时上下文是 HTTP 线程的 ThreadLocal，不能只统计 worker 内部 Store 调用。切面必须把 HTTP 线程上的：

```text
MembershipOrderSnapshotWriteCoordinator.putAndGet
```

整体记入 `REDIS_ORDER_WRITE`。这个时间应当包含：

- 等待 256 bulkhead 许可。
- 最多 1 ms 的微批收集窗口。
- 等待前序 Pipeline。
- 当前 Pipeline 从 Lettuce 调用到返回。

这正是 HTTP 请求真实观察到的 Redis 写入等待时间。禁止虚构无法直接测量的“Redis 服务端排队时间”，也禁止 worker 和协调器重复计时。

增加低基数指标：

```text
membership.payment.redis.write.queue.wait
membership.payment.redis.write.batch.size
membership.payment.redis.write.inflight
membership.payment.redis.write.rejected
```

标签最多使用稳定的 `outcome`，禁止订单 ID、Redis Key、用户 ID、traceId 等高基数标签。

### 任务 7：修正专用绿字日志选择规则

**修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/observability/MembershipPaymentTimingRecorder.java`
- `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml`

当前 `force-log-operations` 非空时会提前返回，导致非白名单操作即使失败、NACK 或超过一秒也不输出。改成并集规则：

```java
if (failure != null
        || "NACK".equals(context.ackAction())
        || elapsedNanos >= properties.slowThreshold().toNanos()
        || properties.detailLogEnabled()) {
    return true;
}
if (properties.forceLogOperations().contains(context.operation())) {
    return true;
}
return sampled(context);
```

`loadtest-realtime` 的强制操作只保留：

```text
ORDER_CREATE,PAYMENT_ATTEMPT
```

结果是：HTTP 两个关键入口继续全量记录，便于比较 `totalMs/redisOrderWriteMs`；PENDING、CLOSING、回调 worker 只在超过一秒、失败或 NACK 时记录。这样既保留慢绿字证据，也避免状态机正常空轮询再次把日志放大到数百 MB。

### 任务 8：实现无业务副作用的正式技术预热

**新增：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/warmup/MembershipPaymentInfrastructureWarmupService.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/warmup/impl/MembershipPaymentInfrastructureWarmupServiceImpl.java`
- `ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/config/MembershipPaymentWarmupRunner.java`

Service 接口只暴露：

```java
void warmUpRedisInfrastructure();
```

Impl 完成：

1. 执行一次 Redis `PING`，建立并验证 Lettuce 连接。
2. 使用 `PathMatchingResourcePatternResolver` 读取 `classpath*:lua/membership-payment/*.lua`，按文件名排序并拒绝重复资源，然后逐个执行 `SCRIPT LOAD`。这样会同时覆盖订单、Provider、回调、状态机和已修改的退款/拒绝回调脚本，避免以后新增脚本却忘记更新预热白名单。
3. 校验至少发现一个脚本，并校验每个 `SCRIPT LOAD` 返回 40 位十六进制 SHA1；不执行任何脚本，因此不创建或修改 Redis Key。
4. 确认微批协调器已经进入 accepting/running 状态，但不提交虚拟订单。

Web `ApplicationRunner` 在 `loadtest-realtime` 显式启用 warmup 后按顺序执行：

```text
warmUpRedisInfrastructure()
  -> RabbitTemplate.execute(channel -> exchangeDeclarePassive(...))
  -> runner 正常返回
  -> Spring Boot readiness 才进入 ACCEPTING_TRAFFIC
```

Rabbit 只对 PAYMENT/CLOSING 两个交换机和对应两个业务队列执行 passive declare，用于建立缓存连接/Channel并验证拓扑存在；禁止发布所谓“预热消息”，因为即使消息体是假的也可能污染 Confirm、Return、DLQ 或消费者统计。

`fail-fast=true` 时任一 Redis 或 Rabbit 技术预热失败都让启动失败，不允许一个已经启用会员支付但依赖不可用的实例进入 readiness。普通 Profile 默认不启用该 runner。

这层预热能够覆盖连接建立、Redis Lua 脚本缓存、协调器线程和 Rabbit 通道创建，但不能完整预热以下业务代码：

- PostgreSQL 首次真实订单事务。
- Provider 创建支付入口。
- Rabbit 真实 Publisher Confirm 往返。
- 整条业务链的 JVM JIT 热化。

上述“真实业务预热波次”必须留到阶段二的受控压测脚本：使用独立测试账号执行少量真实 `ORDER_CREATE + PAYMENT_ATTEMPT`，完成幂等清理后再开始正式计时。它不能放入生产启动代码。

### 任务 9：阶段一源码复核和硬停止

阶段一实现完成后只做人工/静态源码复核，不运行任何验证命令：

- 核对只修改生产 Java、Lua、YAML 和文档。
- 核对所有新增 Service 都有接口 + `Impl`，调用方只注入接口。
- 核对构造器注入、`final` 字段、无请求级可变单例状态。
- 核对新增/修改 Java 顶级类型和非直观并发机制都有中文 JavaDoc/注释。
- 核对 YAML 每个实际配置行前都有紧邻中文注释。
- 核对 Lua 没有批量订单循环，Pipeline 没有被描述为事务或强一致。
- 核对现有用户改动未被覆盖或回退。
- 列出尚未修改的测试文件和所有未执行验证，然后停止等待批准。

---

## 四、阶段二测试代码计划（此时不实施）

只有用户在阶段一交付后再次批准，才修改：

- `RedisMembershipOrderSnapshotStore` 集成测试：四种版本 outcome、16 字段返回、500 条拆成 4 批、重复 orderId 保持顺序、损坏返回拒绝。
- 协调器单元测试：1 条 1 ms flush、128 条立即 flush、256 bulkhead、超时、中断、Pipeline 失败、停机排空。
- 订单创建测试：证明 `put + find` 变成一次 coordinator 调用。
- 支付发起测试：证明保留首尾两个实时 `find`，只移除数据库提交后的中间 `find`。
- 计时测试：证明协调器总等待只计入一次 `redisOrderWriteMs`。
- 日志测试：HTTP 强制全量；非白名单慢/失败/NACK 仍记录；正常快速 PENDING/CLOSING 不记录。
- warmup 测试：只调用 `PING/SCRIPT LOAD/passive declare`，断言没有业务 Redis Key、Rabbit 消息或数据库写入。
- Spring 配置测试：属性边界、Executor Bean、runner 条件和 YAML 绑定。
- loadtest 脚本：新增独立账号真实预热波次和清理检查，但不改变正式样本统计。

阶段二只写测试，不运行；完成后再次停止等待批准。

---

## 五、阶段三验证顺序（此时不执行）

1. Lua/静态契约测试。
2. 协调器和订单 Service 单元测试。
3. Testcontainers Redis 集成测试。
4. `ai-temperate-service`、`ai-temperate-web` Spring 上下文测试。
5. 启动受控 Profile，确认 readiness 前完成技术预热且 Redis/Rabbit 无业务副作用。
6. 使用独立测试账号执行真实业务预热，清理后重置 Redis SLOWLOG 和日志起点。
7. 先用 HTTP 外部并发 256 验证无错误，再保持 JMeter 4,096 并发执行正式 E-P1/E-PR/E-A1 波次。
8. 比较优化前后：
   - `ORDER_CREATE totalMs/redisOrderWriteMs` P50/P95/P99/max。
   - `PAYMENT_ATTEMPT totalMs/redisOrderWriteMs` P50/P95/P99/max。
   - Redis SLOWLOG、`INFO commandstats`。
   - 协调器批次大小、等待时间、在途数和拒绝数。
   - 5,000 同时到期完整收敛时间。

验收目标：

- HTTP 路径不再出现 `put -> HGETALL` 中间往返。
- 5,000 个并发订单写入约形成 40 个最多 128 条的 Pipeline，而不是 5,000 次独立网络往返。
- 应用内 Redis 逻辑在途写入不超过 256。
- 新单订单 Lua 不进入 10 ms Redis SLOWLOG。
- 首波 `redisOrderWriteMs` P95 和最大值显著下降，不再由 Lettuce 无界排队形成 6～7 秒尾延迟。
- 状态机、退款、回调、Rabbit ACK/Confirm 和数据库最终事实语义不变。

---

## 六、失败语义与回滚

- Pipeline 内某个订单失败不回滚同批其他订单；客户端重试通过 stateVersion 和数据库幂等收敛。
- 数据库已经提交而协调器超时，仍返回现有 Redis unavailable 503；worker 尽力完成已入队恢复，不能伪装为数据库回滚。
- Redis Key、Hash 字段和 TTL 不变，无数据迁移。
- 回滚时可以让订单 Service 重新注入 Store 并恢复 `put + find`，关闭 `app.membership-payment.warmup.enabled`，移除协调器 Bean；已有数据无需清理。
- 不允许通过重新放大单条 Lua 的订单数量解决吞吐；如果 Pipeline 128 的尾延迟仍高，下一步只能把批量降到 64/32 或把 `maximum-inflight` 从 256 下调后重新对比。
