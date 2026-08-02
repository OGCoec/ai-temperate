# AI 会话全生命周期诊断交接

## 范围

本次改造只增加 AI 会话时序诊断，不修改退款、扣费、SSE 终态或个人资料刷新规则，也没有增加 500 毫秒数据库轮询、消息队列或数据库表。

诊断组合使用服务端 Trace Filter、公开 Service AOP、Reactor 显式终态探针、线程池上下文传递、事务同步回调、缓存失效日志和前端 Abort/展示探针。AOP 不主动订阅 `Flux` 或 `Mono`。

## 开关

后端默认关闭：

```text
AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED=false
AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_SAMPLE_RATE=0
```

H5 与 Android 前端构建默认关闭：

```text
AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED=false
```

受控复现时，后端和目标前端构建同时设置 `AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED=true`，后端临时设置 `AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_SAMPLE_RATE=1.0`。复现结束后恢复默认值并重新部署对应产物。

关闭时后端使用无状态 no-op 实现，且不注册 AI Trace Filter 和生命周期 AOP；前端使用冻结的 no-op 对象。

## 时序判断

- `CLIENT_ABORT_CALLED` 到 `REACTOR_CANCEL_OBSERVED` 慢：浏览器、代理、Servlet 或 Reactor 取消传播慢。
- `FINALIZER_SUBMITTED` 到 `FINALIZER_STARTED` 慢：终态线程池排队。
- `FINALIZER_STARTED` 到 `SETTLEMENT_TRANSACTION_COMMITTED` 慢：连接池、数据库锁或事务写入慢。
- 事务已提交但 `PROFILE_CACHE_EVICTION_COMPLETED` 慢或缺失：Redis 缓存失效路径异常。
- 数据库和缓存均已完成，但没有 `PROFILE_REFRESH_STARTED`：前端没有重新读取个人资料。
- 刷新资料后才出现 `PROFILE_QUOTA_CHANGED`：退款已经落库，先前页面展示的是旧额度。

统一日志事件为 `event=ai_conversation_lifecycle`。日志只包含 Trace、公共 ID、固定枚举、布尔值、字符数和耗时；禁止加入正文、Token、Cookie、原始幂等键、内部数据库 ID、余额值或第三方异常原始消息。

## 第一阶段状态

源码和测试源码已编写。本阶段没有运行 Maven、Node、Spring 上下文、浏览器、PostgreSQL、Redis 或 RabbitMQ 验证，也没有执行数据库迁移。只有在用户再次明确确认第二阶段的具体命令和隔离基础设施范围后，才能执行这些验证。
