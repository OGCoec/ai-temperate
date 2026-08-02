# AI 会话后台 Generation 发布与回滚

## 前置条件

功能源码默认启用。启动应用前必须确认：

- RabbitMQ 已安装并启用 `rabbitmq_delayed_message_exchange` 插件。
- Broker `consumer_timeout` 大于模型最长 15 分钟至少 5 分钟，建议不少于 20 分钟。
- 每个实例的 `AI_CONVERSATION_INSTANCE_ID` 全局唯一。
- PostgreSQL 已依次执行 011 Generation 和 012 Payload 建表脚本，且孤儿检查为空。
- RabbitMQ Publisher Confirm、Return、Quorum Queue、手动 ACK 和 DLQ 均已验证。
- 隔离测试需要通过 `AIT_TEST_RABBIT_DELAYED_IMAGE` 显式提供已经启用 delayed-message 插件的 RabbitMQ 镜像；源码不内置未经确认的第三方镜像。
- 默认四个 Generation Worker 和四个 Terminal Consumer 合计最多八个并发业务任务；提高消费者或实例数前必须结合 Hikari 上限与 PostgreSQL `max_connections` 重新计算，Worker 调用模型期间不得持有数据库连接。

## 环境变量

```text
AI_CONVERSATION_ASYNC_GENERATION_ENABLED=true
AI_CONVERSATION_INSTANCE_ID=<deployment-unique-instance-id>
AI_CONVERSATION_DETACH_GRACE=30s
AI_CONVERSATION_OBSERVER_HEARTBEAT=1s
AI_CONVERSATION_TERMINAL_RETENTION=24h
AI_CONVERSATION_WORKER_CONSUMERS=4
AI_CONVERSATION_MAX_WORKER_DURATION=15m
```

前端构建使用：

```text
AI_CONVERSATION_ASYNC_GENERATION_ENABLED=true
```

前后端开关必须按发布步骤协调，不得让新前端连接尚未部署的新 API。

正常 TCP 关闭或 App 强杀时，一秒 Observer 心跳用于尽快暴露写通道失效；检测完成后才开始三十秒宽限，因此 31～32 秒是正常网络条件下的目标而不是跨代理绝对保证。刷新发生在响应头到达前时，前端保留原 UUIDv4 幂等键并通过恢复接口找回同一 Generation，绝不重新创建第二个模型任务。

## 发布顺序

1. 依次执行 `sql/011_create_ai_conversation_generation.sql` 和 `sql/012_create_ai_conversation_generation_payload.sql`，并声明 RabbitMQ 拓扑。
2. 部署 Generation Worker、Control、Detach 和 Terminal Consumer。
3. 单实例内部用户启用，验证正常完成、Stop 和资金终态。
4. 增加第二实例，验证 Owner 定向控制和重复消息幂等。
5. 发布前端全局 Generation Manager、刷新恢复和多会话 Observer。
6. 启用 30 秒失联策略。
7. 最后开放管理员取消接口。

## 监控

重点观察：

```text
ai.conversation.generation.queued
ai.conversation.generation.started
ai.conversation.observer.attached
ai.conversation.observer.detached
ai.conversation.detach.check.stale
ai.conversation.detach.check.expired
ai.conversation.cancel.requested
ai.conversation.terminal.published
ai.conversation.billing.duration
ai.conversation.rabbit.confirm.failures
ai.conversation.rabbit.dead.letters
ai.conversation.reconcile.required
```

同时监控 RabbitMQ Ready/Unacked/DLQ、Hikari 等待、PostgreSQL 事务时间、Redis 命令延迟和缓存写失败。指标标签不得包含正文、用户 ID、完整 Redis Key 或原始幂等键。

## 回滚

1. 先停止创建新的异步 Generation。
2. 等待已有任务进入终态，或按 Generation 状态人工处理；运行中不得切换结算引擎。
3. 确认活动任务清空后，再发布关闭新行为的前端。
4. 保留表、Exchange、Queue 和 DLQ，不执行破坏性回滚。
5. `RECONCILE_REQUIRED` 和 DLQ 消息必须在回滚前后持续保留并核对。

## 当前验证状态

本地生产源码编译成功；前端聊天测试五十六项通过，Generation 数据库与 Rabbit 配置契约、核心 Service 和 Web Controller 测试通过。PostgreSQL 15 Testcontainers 迁移测试五项、Redis 7.4 双客户端 A/B Pub/Sub 测试三项、Worker 假模型终态测试五项均通过。

HBuilderX 5.15 H5 构建成功；把 `public/_headers` 与 `public/_redirects` 显式复制到 `unpackage/dist/build/web` 后，发布产物校验通过。当前 HBuilderX 发布命令不会自动复制这两个文件，部署流水线必须补充复制步骤。前端发布契约仍有一个与 Generation 无关的既有 ESM 导出失败，完整安全配置测试也仍有一个既有 CSRF Cookie `SameSite` 断言失败。

本次没有执行真实数据库迁移或连接生产基础设施。RabbitMQ delayed-message 单节点可靠性、真实三十秒延迟和三节点 Quorum Leader 故障切换均已在本机隔离 Docker 通过；Redis A/B 与 Owner 定向取消也已在隔离容器 Broker 验证。真实预发布迁移、部署态 A/B 应用全链路、Android、外部 Chrome 端到端和 200 次 P95 时效验收仍未执行。不得据此认定完整发布流程已经通过。
