# 访问请求审计数据关系与清理说明

`access_request_audit.user_id` 与 `userloginidentity.id` 是应用层逻辑关系，不建立物理外键。

审计写入发生在业务请求完成之后，经 RabbitMQ 异步批量落库。用户被删除后，已有审计记录仍按默认 30 天安全保留期保存；清理任务按 `occurred_at, id` 有序分批删除，不依赖用户表级联。

数据恢复和排查边界：

- `message_id` 唯一索引用于重复消息幂等，不代表消息 Exactly Once。
- `user_id` 只用于内部安全分析，不通过 API 返回。
- IP 仅保存 IPv4 `/24` 或 IPv6 `/48` 前缀以及独立密钥生成的 HMAC。
- 不保存完整 IP、请求体、Cookie、Authorization、邮箱、手机号或设备安装 ID。
- RabbitMQ 或 PostgreSQL 审计链路故障不得改变原业务响应，因此请求完成与成功发布之间仍存在可接受的丢失窗口。

孤儿数据检查使用 [access_request_audit_orphans.sql](../../sql/checks/access_request_audit_orphans.sql)。发现孤儿记录时只核实用户删除流程和保留期任务，不应在 30 天保留期内人工级联删除。
