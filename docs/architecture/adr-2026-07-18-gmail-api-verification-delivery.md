# ADR: Gmail API 验证码投递与临时丢弃最终失败消息

## 状态

Accepted as temporary exception, 2026-07-18.

## 背景

项目规范默认要求邮箱发送使用 `JavaMailSender`，RabbitMQ 最终失败消息进入死信队列。本次验证码投递改造的目标是：

- 邮箱验证码使用 Gmail API HTTP/WebClient 投递，替代当前 SMTP 投递。
- 邮箱和短信验证码统一进入 RabbitMQ 延迟消息流程。
- 第 6 次供应商调用仍失败，或下一次延迟已经超过验证码过期时间时，最终补偿 Redis 状态、ACK 当前消息并直接丢弃。

这两个目标与现有规范存在临时冲突，必须显式记录风险和回滚路径。

## 决策

1. 邮箱验证码投递使用 Gmail `users.messages.send` API，成功条件为 HTTP 2xx 且响应 `Message.id` 非空。
2. 邮箱、短信验证码发送入口不再同步调用供应商，而是发布 durable `x-delayed-message` 交换机消息。
3. 消费者手动 ACK：成功回写 Redis 后 ACK；可重试失败在发布下一条延迟消息并释放状态后 ACK；发布重试消息失败则不 ACK，让当前消息重新投递。
4. 本阶段最终失败不进入 DLQ，而是执行最终补偿、ACK 并丢弃。
5. `operationId` 使用 NanoId 38 位 URL-safe 原文生成，只在生成瞬间作为 HMAC 输入；Redis 与 MQ 只保存 HMAC 后的 Base64URL 摘要。

## 风险

- 不进入 DLQ 会降低失败样本的事后排查能力，只能依赖日志和指标观察最终丢弃数量。
- Gmail API 凭据、refresh token 或网络出口异常会导致验证码投递不可用，需要通过可重试分类、超时和指标尽早暴露。
- 项目不使用 Outbox，Redis 状态登记与 RabbitMQ 发布确认之间仍存在非原子窗口，不能声明 Exactly Once。
- RabbitMQ 延迟交换机依赖 `rabbitmq_delayed_message_exchange` 插件，目标环境必须提前启用。

## 缓解

- 所有 RabbitMQ exchange、queue、message 均设置 durable/persistent，并等待 publisher confirm。
- Redis 中的 claim/release/success/final failure 都按当前 operationId 原子判断，避免旧消息误删新验证码。
- 验证码明文和目标地址进入 MQ 前使用 AES-GCM 加密，Redis 仍只保存验证码摘要。
- 通过 `ait.auth.verification.delivery.*` 指标观测发布、消费、重试、最终丢弃结果。

## 后续恢复路径

1. 搭建 admin 服务和死信处理页面后，为 email/sms queue 增加 DLQ 绑定。
2. 将最终失败分支从 “ACK 丢弃” 改为 “ACK 前发布/路由到 DLQ”，并在 DLQ 中保留脱敏诊断字段。
3. 若需要恢复规范默认邮件栈，可新增 `JavaMailSender` 策略实现并通过策略注册表切换，保留 RabbitMQ 投递状态机不变。
