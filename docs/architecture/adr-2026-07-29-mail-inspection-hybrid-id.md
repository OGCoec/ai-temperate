# ADR：邮件检查任务使用 128 位 Hybrid Base64URL ID

- 状态：已接受
- 日期：2026-07-29
- 范围：管理员邮件检查任务

## 背景

项目通用资源 ID 由正数 `Long` 编码为固定 11 字符 Base64URL。邮件检查任务不是
PostgreSQL 资源，而是跨 Redis、RabbitMQ、SSE、H5 和 Android 传播的短生命周期任务。
该任务需要在 JVM 重启后继续可寻址，并避免继续沿用旧进程内 Snowflake `Long` 标识。

## 决策

邮件检查任务采用 `HybridSemaphoreIdWorker` 生成的 16 字节标识，并通过
`Base64.getUrlEncoder().withoutPadding()` 编码为固定 22 字符公开 `jobId`：

```text
^[A-Za-z0-9_-]{22}$
```

`HybridBase64UrlCodec` 必须执行长度、字符集、解码长度和规范重编码校验。该例外只适用于
`/api/admin/mail-inspection/jobs/{jobId}` 资源；项目通用 `SnowflakeIdWorker`、
`PublicIdCodec` 和其他业务的 11 字符资源 ID 保持不变。

Redis Key 禁止包含公开 `jobId`。服务端使用独立的至少 32 字节随机密钥，对
`mail-job-id:v2\0<jobId>` 执行 HMAC-SHA256 Base64URL。客户端幂等 ID 使用独立域
`mail-client-request-id:v2\0<clientRequestId>`。Base64URL 只提供编码，不提供授权；
所有 GET、resume 和 SSE 入口继续执行管理员会话、Edge、PreAuth、网络风险和资源访问校验。

RabbitMQ 消息一次性升级为 schema v2，只携带 22 字符 `jobId` 与完整 `jobKeyHash`，
不保留 `jobInternalId` 或旧消息双读。

## 影响与风险

- 新旧前端、Worker、后端和 Rabbit 消息契约不能混用，必须整体发布。
- 22 字符 ID 不兼容通用 11 字符 Converter，因此邮件模块使用专用 Converter。
- HMAC 密钥丢失会使已有 Redis 任务无法寻址；密钥必须由 Secret 管理系统备份和轮换。
- Hybrid ID 不是权限边界；即使 ID 难以猜测，也必须保留管理员认证与资源级校验。
- 任务生命周期完全由 Redis TTL 决定，Redis 不可用时邮件任务 Fail Closed，不回退 JVM 内存。

## 回滚

回滚必须同时回滚后端、Cloudflare Worker、H5 和 Android 契约。回滚前若 RabbitMQ
仍存在 schema v2 消息，必须继续使用新版本安全处理至 Ready/Unacked 为零；禁止由旧版本
消费、自动 ACK 或 purge。新 Redis `v2` 命名空间保留用于诊断，不立即删除。

如要永久撤销本 ADR，应另写 ADR，明确新任务 ID 迁移策略、Rabbit 消息清空条件、
Redis 诊断数据保留期和客户端强制升级方式。
