# 会员订单 Redis 写入 64×6/384 设计

## 目标

在不增加 Redis 逻辑写入总在途的前提下，把旧的两个 192 条重 Pipeline 拆成六个最多 64 条的轻 Pipeline，降低单 lane 头阻塞和 HTTP 尾延迟。生产环境和正式压测使用同一配置，避免只优化测试脚本。

## 固定生产与正式压测合同

```text
Redis Pipeline 单批上限：64
Redis lane 数量：6
Redis 逻辑写入总在途：384
最大同时执行 Pipeline：6
```

Java 和 Spring 配置仍保留 `batchSize=1～192`、`laneCount=1～6` 的受控回滚能力；正式编排器、启动器、采样器和报告器只接受精确 `64/6/384`。发现运行探针漂移时，本轮测试无效。

## 协调模型

订单 ID 使用现有稳定哈希映射到 lane0～lane5。每条 lane 各有一个串行 Worker，并分别维护 `FULL_RESTORE` 与 `PAYMENT_ATTEMPT_PATCH` 两个有界队列。两类队列共享全局 384 个公平许可，均有数据时按四个创建批次后一个支付批次调度；一类为空时立即执行另一类。

同一订单不会跨 lane，支付 Patch 只有在对应创建快照 Future 成功后才能进入队列。每条 lane 只能有一个正在执行的 Pipeline，因此最多六个 Pipeline 同时执行；每批仍把最多 64 条独立单订单 `EVALSHA` 加入 Pipeline，不改变 Lua 原子边界。

## 失败与关闭

- 领取许可、入队、等待结果和 Pipeline 执行都有界。
- 批次异常只失败该批次的 Future，成功与失败路径均精确释放许可。
- `SCRIPT FLUSH`、连接异常、返回数量不足和关闭排空必须受控收敛。
- 不允许无界提交 Lettuce 命令，不允许提前返回 HTTP，也不改变 RabbitMQ Confirm 合同。

## 证据合同

运行时 CSV 保留 lane0/lane1 旧列，并新增 lane2～lane5。每次采样必须验证：

```text
每条 lane 总深度 = FULL_RESTORE 深度 + PAYMENT_ATTEMPT_PATCH 深度
inflight + availablePermits = 384
0 <= inflight <= 384
实际 Pipeline 批量 <= 64
实际 lane 编号属于 0～5
```

最终 JSON 和 Markdown 必须明确记录 Pipeline 64、lane 6、总在途 384。数据库 `created_at` 的密集程度只能辅助诊断；正式 QPS 继续使用服务端 HTTP `min(receivedAt) → max(completedAt)` 计算。

## 验收与回滚

先执行定向配置、协调器、Redis集成和PowerShell合同测试，再构建并冻结唯一 JAR。Canary 使用真实 E-P1 同规模预热、精确清理和正式5K；功能和环境有效后才进入八段5K与八段10K。

回滚方法及64条特例理由以 [ADR](../../architecture/adr-2026-08-26-membership-payment-redis-write-64x6.md) 为准。旧 `192×2/384` 文档只保留为历史基线。
