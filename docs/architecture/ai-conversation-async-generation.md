# AI 会话后台 Generation 架构

## 状态边界

后台生成开启后，模型 SDK 流由 RabbitMQ Generation Worker 持有，浏览器 SSE 只观察 Redis 快照和 Pub/Sub 增量。

```text
CLIENT_DETACHED != CANCEL_REQUESTED
SSE_ERROR != BILLING_TERMINAL
```

- 页面隐藏、站内路由切换和 Observer 传输异常不产生退款事实。
- 用户 Stop、管理员取消和同一 observer epoch 持续失联超过宽限期，才写入 `CANCEL_REQUESTED`。
- Worker 通过数据库终态版本 CAS 冻结唯一事实；RabbitMQ 不传递退款指令。
- Billing Consumer 继续调用既有 `AiConversationTerminalBillingPolicy` 和 `AiConversationSettlementService`，不复制金额算法。
- PostgreSQL 是任务状态和资金结果的权威来源；Redis 是可丢失、可重建的展示快照。

## RabbitMQ 一致性边界

Generation、Control、Detach Check 和 Terminal 消息使用持久消息、Durable Exchange、Quorum Queue、Publisher Confirm 与手动 ACK。即时消息使用 mandatory/Return；`x-delayed-message` 消息只以 Confirm ACK 作为 Broker 接受依据。

本项目不使用 Outbox，PostgreSQL 提交与 RabbitMQ 发布不具备原子性，也不宣称 Exactly Once。提交后发布空窗由原有一分钟运维频率下的有界恢复查询补发，实时主链路不扫描数据库。

重复消息依靠以下约束收敛：

- 幂等摘要和 Usage 唯一约束。
- Generation 状态预期值更新。
- `terminal_version` CAS。
- Billing Consumer 对 Generation 和 Usage 加锁。
- 成功消息 ID 先绑定 Payload，附件终态重试复用同一路径。

## Observer 与 30 秒宽限

每次观察连接都会增加 `observer_epoch`。SSE 使用可配置的一秒轻量心跳尽快暴露真正失效的写通道，但不会把页面隐藏当作取消。旧连接结束时只有携带当前 epoch 才能把状态改为 `DETACHED`。延迟检查到期后还会再次核对 epoch、状态和 `detached_at`；任一不匹配都作为陈旧消息 ACK。

前端 Generation Manager 持有多个会话的 Observer。组件卸载或切换会话只取消本地 UI 订阅，不关闭全局 Observer。浏览器刷新后通过 Generation ID 或原 UUIDv4 幂等键恢复任务，并以“先订阅、再快照、最后按 revision 去重”的顺序补齐输出。

当前按明确产品决策不做 Redis 内部分片：每个 Generation 的 revision、delta 与展示终态保存在一个独立 Hash，每个会话的上下文和压缩结果也保存在该会话自己的一个独立 Hash。不同 Generation 和不同会话仍使用不同 Key。BigKey 风险、监控阈值和后续迁移方式记录在 `adr-2026-08-01-ai-generation-redis-unsharded-snapshot.md`。

## 终态与资金

| Worker 事实 | 计费处理 |
| --- | --- |
| `COMPLETED` 且有完整 Token Usage | 按真实 Token 与倍率快照结算 |
| xAI 图片 `COMPLETED` 且每张成功图有合法 `cost_in_usd_ticks` | 汇总成功图成本，换算额度后多退少补 |
| xAI 图片有效但至少一张成功图缺少合法成本 | 图片照常持久化，保留全部预扣并进入 `RECONCILE_REQUIRED` |
| `UPSTREAM_FAILED` | 无论有无部分文本均全额退款 |
| `SYSTEM_FAILED` | 无论有无部分文本均全额退款 |
| 用户、管理员或失联取消且无输出 | 全额退款 |
| 取消且有最终 Usage | 按真实 Usage 结算 |
| 取消且只有部分文本 | 按既有 UTF-8 三字节向上取整规则估算 |
| 证据不足或有限重试耗尽 | `RECONCILE_REQUIRED` |

退款、额度、Usage、Detail 和 Generation 状态在同一个 PostgreSQL 本地事务中提交；提交成功后 Terminal 消息才 ACK。

## 数据保留与恢复

- `SETTLED` 和 `REFUNDED` 的 Generation/Payload 保留 24 小时后，每分钟最多按既有批量上限清理一批。
- 表间只使用逻辑关联：创建时先验证用户、会话、模型和 Usage，冻结的 `vendor_snapshot` 与 `metering_basis` 必须在 Usage、Detail 和 Payload 间一致；终态清理必须先批量删除 Payload、再按同一批 Generation ID 删除 Generation，中途失败由同一 PostgreSQL 事务回滚。
- `RECONCILE_REQUIRED` 不自动清理，避免丢失人工核对证据。
- `QUEUED` 发布空窗、`CANCEL_REQUESTED` 控制消息空窗和 `TERMINAL_PENDING_BILLING` 终态消息空窗可被有界恢复补发。
- `RUNNING` 超过 Worker 最大时限再加一个扫描周期仍无终态时，按 Owner 丢失冻结为系统失败。
- 取消发生在 Worker 领取之前时没有 Owner，提交后直接以终态 CAS 冻结无输出取消，不会把控制命令误发到接收 API 的实例。
- 已有 Owner 的取消命令若持续无法送达，达到 Worker 最大时限后按 Owner 丢失冻结系统失败并全额退款，禁止永久停留在 `CANCEL_REQUESTED`。
- Worker 在 Payload 中只保存 Redis 上下文 generation 与 ephemeral ordinal 游标；正常消息事务提交后 Billing Consumer 才把临时轮次升级为持久轮次，合并失败则删除派生缓存并从 PostgreSQL 重建。

迁移和孤儿检查分别位于：

- `sql/011_create_ai_conversation_generation.sql`
- `sql/012_create_ai_conversation_generation_payload.sql`
- `sql/migrations/024_add_ai_conversation_metering_basis.sql`
- `sql/checks/ai_conversation_generation_orphans.sql`
- `sql/checks/ai_model_usage_detail_orphans.sql`

没有物理外键意味着数据库无法绝对阻止跨表孤儿，这是本方案明确接受的风险；发布前和异常恢复后必须运行 Generation、Payload、Usage、Detail、消息以及计量依据一致性检查，发现异常时保留证据并进入人工核对，禁止猜测扣费。
