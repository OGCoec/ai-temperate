# ADR：Microsoft Graph 邮箱验证码与按投递尝试随机选择供应商

## 状态

Accepted as temporary exception, 2026-07-19。

## 背景

项目规范默认要求邮件使用 `JavaMailSender`，现有验证码投递已经通过
`adr-2026-07-18-gmail-api-verification-delivery.md` 临时批准 Gmail REST API。
当前部署环境还需要使用个人 Microsoft 邮箱的 Graph `sendMail` API，并希望首次投递及每次
RabbitMQ 延时重试都在 Gmail 与 Microsoft Graph 之间重新选择供应商。

该设计继续偏离默认 SMTP 邮件栈，并引入“供应商接受请求但响应丢失后，另一供应商重复发送”
的明确风险，因此需要单独记录决策、缓解措施和回滚方式。

## 决策

1. Microsoft 邮件使用官方 Microsoft Graph Java SDK 和个人账户委托权限 `Mail.Send`。
2. 短期 access token 由部署环境中的 refresh token 换取并在内存缓存；Secret、Token 和完整邮箱
   不进入日志、异常、Redis 或 RabbitMQ 元数据。
3. Graph SDK 使用无 RetryHandler 的自定义 OkHttpClient，并关闭连接自动重试。除了未被服务端接受的
   401 可刷新一次令牌外，全部业务重试由 RabbitMQ 统一管理。
4. 邮件供应商根据当前 RabbitMQ `messageId` 的稳定二元哈希分桶：
   `0 -> Gmail`，`1 -> Microsoft Graph`。
5. 延时重试消息生成新的 `messageId`，因此重新选择供应商；Broker 原样重投同一消息时保持原选择。
6. 消息不新增 Provider 字段，`operationId` 和加密 payload 在重试间保持不变，所以重复邮件包含同一个
   六位数验证码。
7. Graph `sendMail` 无异常返回只表示收到 HTTP 202 Accepted，不声明邮件最终送达，也不伪造供应商消息 ID。

## 风险

- Gmail 或 Graph 已接受邮件但客户端超时后，下一次随机重试可能通过另一供应商再次发送相同验证码。
- 环境变量保存的 refresh token 无法由应用持久化轮换；Graph 返回的新令牌只在当前进程内使用，重启后
  仍依赖 Secret 管理系统提供的值。
- 任一邮件供应商缺少凭据时，对应 Spring 策略不会注册；随机命中该供应商将形成受控失败。
- 项目不使用 Outbox，Redis 状态变更和 RabbitMQ 发布确认之间仍存在已接受的非原子窗口。

## 缓解

- 验证码摘要、失败次数、TTL 和成功消费仍由 Redis Lua 原子处理，不依赖邮件供应商校验。
- RabbitMQ 有限重试受验证码过期时间约束，不允许无限重新入队。
- 指标增加有限集合的 `provider` 标签，用于观察 Gmail、Microsoft Graph 的选择分布、成功和失败结果。
- 只记录固定安全错误分类，不记录第三方原始响应体、验证码或完整目标地址。

## 回滚

1. 删除 Microsoft Graph Service Bean 和对应 Registry 枚举。
2. 将邮件投递解析恢复为固定 `GMAIL`。
3. 删除 Microsoft Graph OAuth 配置和 Maven 依赖。
4. 如需恢复项目默认邮件栈，按既有 ADR 的恢复路径增加 `JavaMailSender` 策略，同时保留 RabbitMQ
   投递状态机和 Redis 校验边界。
