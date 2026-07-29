# 管理员邮件检查 Redis 与 SSE 运维手册

## 固定运行契约

邮件检查以 Redis 为唯一事实来源。Redis Pub/Sub 只用于唤醒 SSE 实例，不保存历史；
连接建立和心跳校准都重新读取 Redis revision。活动任务使用 15 分钟滑动租约，后端每
30 秒续租；终态使用固定 15 分钟保留，GET/SSE 读取不续期。

结果每 32 行一个 Redis Hash Bucket，SSE 快照每批最多 100 行。单任务最多 10,000 行。
Redis 不可用时创建接口返回 503，Rabbit 消费者暂停且消息不能 ACK，不允许进程内回退。

## Redis Key 与 Secret

Key 固定使用：

```text
ait:<env>:admin-mail:job:v2:meta:<jobHash>
ait:<env>:admin-mail:job:v2:counts:<jobHash>
ait:<env>:admin-mail:job:v2:results:<jobHash>:<bucket>
ait:<env>:admin-mail:job:v2:idempotency:<requestHash>
ait:<env>:admin-mail:job:v2:active:<inspectionType>
ait:<env>:admin-mail:job:v2:acceptance:<inspectionType>
ait:<env>:admin-mail:job:v2:revision:<jobHash>
ait:<env>:admin-mail:job:v2:events
```

部署必须提供 `ADMIN_MAIL_INSPECTION_JOB_HMAC_SECRET_BASE64`，值为独立、规范 Base64
编码的至少 32 字节随机密钥，不得复用 Rabbit 载荷密钥、会话密钥或 Edge 签名密钥。
日志只允许使用 `jobHash` 前 16 字符作为 `jobRef`，指标标签禁止携带 Job ID、完整 Key
或 `jobRef`。

Redis 必须启用磁盘持久化，建议 AOF `appendfsync everysec` 与
`maxmemory-policy noeviction`。禁止生产执行 `KEYS *`；诊断使用有界 `SCAN`。

## 发布前检查

1. 停止旧后端与前端发布。
2. 确认 Submission、Work、Marker、DLQ 的 Ready/Unacked 全部为零。
3. 任一旧消息不为空时终止切换，不 purge、不自动 ACK。
4. 确认 `ait:<env>:admin-mail:job:v2:*` 命名空间为空。
5. 确认 Job HMAC Secret、Rabbit AES Secret 与 Edge Secret 均存在且彼此独立。
6. 确认 Redis 持久化和无驱逐策略生效。

发布顺序固定为后端、Cloudflare Worker、H5、Android。启动后确认四种检查类型均为
`ACCEPTING`，再使用隔离数据创建任务并检查 Redis、Rabbit、SSE、traceId 与 CF-Ray
关联，最后开放管理员入口。

## SSE 诊断

正常连接依次出现：

```text
snapshot-meta
result-batch（零到多条）
sync-complete
progress / result / status
terminal
```

`snapshot-meta` 和 `result-batch` 不带 SSE `id`；客户端只在 `sync-complete` 或实时事件
后保存 revision。客户端 45 秒没有收到事件时按 1、2、5、10、30 秒有限重连，耗尽后显示
“实时连接已中断”，不回退 HTTP 轮询。页面隐藏或卸载会关闭连接，重新显示时携带
`Last-Event-ID` 建连。

排查丢事件时先比较 Redis revision 与客户端 `lastRevision`。Redis revision 更大而没有
Pub/Sub 日志，等待下一次 15 秒心跳触发快照校准；不得为此重复执行邮箱业务。

## 故障处理

- Redis 写失败：保持 Rabbit 消息未 ACK，暂停对应消费者，修复 Redis 后再恢复。
- Pub/Sub 发布失败：不撤销 Redis 状态，不重做邮箱业务；观察 SSE 心跳校准指标。
- 任务不存在：确认终态保留是否超过 15 分钟，并检查同批 Key 是否共享同一绝对过期时间。
- SSE 429：同一管理员会话已达到四条连接，关闭旧页面或等待旧连接异步完成。
- Worker 缓冲：确认 `Content-Type: text/event-stream`、
  `Cache-Control: no-store, private, no-transform` 和 `X-Accel-Buffering: no`。
- Rabbit 有 v2 消息但 Redis 文档缺失：保持消费者停止，进入受控恢复/人工诊断，禁止构造
  内存任务或直接 ACK。

## 回滚

整体回滚后端、Worker、H5 和 Android。若存在 schema v2 Rabbit 消息，必须先由新版本
安全处理完毕。新 Redis 命名空间保留诊断，待明确保留期和审批后再使用 `UNLINK` 有界清理。
