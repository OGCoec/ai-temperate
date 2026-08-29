# ADR：会员订单 Redis 写入采用 64×6/384

- 状态：Accepted
- 日期：2026-08-26
- 范围：会员订单创建与支付尝试的 Redis 快照写入协调器
- 取代：会员订单 Redis 写入 `192×2/384`

## 背景

会员订单 HTTP 成功响应必须等待 PostgreSQL 本地事务提交、Redis 单订单 Lua 写入以及 RabbitMQ 持久化消息 Publisher Confirm。旧配置把 384 个逻辑写入拆成两条 lane，每条 Pipeline 最多 192 条。已有分层证据显示，数据库事务并不是首要瓶颈，而 Redis 协调器排队和较重 Pipeline 的尾延迟会延长完整 HTTP 墙钟时间。

项目通用 Redis 规范建议每批 100～500 个 Key，但该建议面向一般批量 Key 操作。本链路的每个 Pipeline 元素是独立单订单 `EVALSHA`，并且 HTTP 必须逐订单等待结果。为了降低头阻塞和尾延迟，本 ADR 批准单批 64 条这一会员支付专用例外。

## 决策

生产默认值与正式压测固定为：

```text
batchSize=64
laneCount=6
maximumInflight=384
```

执行结构为：

```text
384 个全局公平许可
→ 订单 ID 稳定散列到六条 lane
→ 每条 lane 一个串行 Worker
→ 每条 lane 同时最多执行一个、最多 64 条独立 Lua 的 Pipeline
```

该决策只重新切分相同的 384 条逻辑写入容量，不扩大总在途。协调器仍允许 `batchSize` 在 1～192 之间、`laneCount` 在 1～6 之间，以支持受控 A/B 和回滚；生产与正式压测证据只接受精确的 `64/6/384`。

## 必须保持的不变量

- 同一订单通过稳定散列固定进入同一 lane，创建完整快照先于对应的支付增量 Patch。
- 每条 lane 内 FIFO，单 lane 同时最多一个 Pipeline；六条 lane 合计最多六个 Pipeline。
- 创建和支付继续按 `4:1` 批次调度；支付最多等待四个创建批次。
- 全局始终满足 `inflight + availablePermits = 384`，且两者均位于 0～384。
- 单个 Pipeline 只包含独立单订单 Lua，不把 64 个订单合并成一个大型 Lua。
- Pipeline 失败只完成对应批次的 Future，并释放全部许可；不得提前返回 HTTP、静默成功或污染其他 lane。
- PostgreSQL、Redis Lua、RabbitMQ、HTTP API、Hikari、Tomcat及状态机合同均不改变。

## 风险与监控

六条 lane 会增加 Worker 数量、Pipeline 交错和线程切换。Redis 服务端仍按自身执行模型处理命令，Lettuce 共享连接也可能成为下一阶段瓶颈，因此不能假设 lane 增多必然提高吞吐量。

发布和压测必须监控：

- 六条 lane 的总队列、创建队列和支付队列深度及偏斜；
- Redis Pipeline execute P95/P99、许可等待和队列等待；
- 全局在途与可用许可；
- HTTP 503、Redis拒绝和超时；
- RabbitMQ Confirm、CPU和线程切换。

只有真实服务端 HTTP 事件证明 QPS、墙钟和有效并发改善，同时功能与可靠性门禁保持为零错误，才能判定新配置优于旧基线。

## 回滚

若 `64×6` 导致 QPS 下降、队列持续增长、Redis连接异常、批次级503或线程切换成本显著上升，只修改部署环境变量回滚：

```text
MEMBERSHIP_PAYMENT_REDIS_WRITE_BATCH_SIZE=192
MEMBERSHIP_PAYMENT_REDIS_WRITE_LANE_COUNT=2
MEMBERSHIP_PAYMENT_REDIS_WRITE_MAXIMUM_INFLIGHT=384
```

回滚不修改数据库、Redis数据、Lua、RabbitMQ消息或业务协议。回滚运行必须以旧配置单独生成 Run ID 和证据，禁止冒充 `64×6/384` 正式结果。
