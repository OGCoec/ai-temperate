# ADR：AI Generation Redis 快照暂不分片

## 决策

每个 `generationPublicId` 使用一个独立 Redis Hash 保存 revision、流式 delta 和展示终态；每个 `conversationPublicId` 使用一个独立 Redis Hash 保存该会话的上下文、压缩摘要和临时轮次。本阶段不在单个 Generation 或单个会话内部继续拆分 Redis Key。

## 原因

当前首要目标是先验证模型生命周期与 SSE 解耦、三十秒失联取消和 RabbitMQ 唯一终态结算。此时引入分片游标、跨 Key 快照和批量读取会扩大并发竞态与恢复测试范围，用户明确决定暂时保持单 Hash 实现。

## 风险

长回答或长期上下文可能形成 BigKey，增加 Redis 单命令延迟、网络传输量和删除成本。该风险不得通过把 Redis 描述为可靠存储来掩盖；PostgreSQL 仍是持久消息与资金结果的权威来源，Redis 快照丢失时只能降级重建或等待最终持久化结果。

## 约束与观测

- 禁止逐 Token 写入；Worker 继续按既有流式批次写入 delta。
- 终态保留期结束后依赖 TTL 回收；若后续增加主动删除，必须使用 `UNLINK`。
- 发布后必须监控 Generation 快照的字段数、内存大小和 Redis 命令延迟。
- 单条 Redis 命令超过十毫秒、字段数接近五千或 Key 大小持续增长时，必须重新评审本决策。

## 替代方案

备选方案是按固定字节大小拆分回答 Key，使用元数据 Key 保存 revision 和分片索引，并以批量 `MGET` 恢复快照。该方案本阶段不实施。

## 回滚与后续演进

若监控证明单 Hash 已影响 Redis 延迟，应新增版本化的分片 Key 格式，采用双读或旧 Key 失效后重建方式迁移；禁止在运行中的 Generation 上原地改变 Key 结构。关闭异步 Generation 功能可立即停止创建新的快照，现有 Key 由 TTL 回收。
