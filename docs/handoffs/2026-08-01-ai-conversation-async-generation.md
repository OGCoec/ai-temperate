# AI 会话后台生成与 RabbitMQ 终态结算交接

## 本阶段交付范围

本阶段已经编写后台 Generation 源码、数据库迁移、RabbitMQ 拓扑、Redis Observer、用户与管理员取消接口、终态计费消费者、前端全局 Generation Manager、恢复接口以及对应测试源码。

新链路由 `AI_CONVERSATION_ASYNC_GENERATION_ENABLED` 控制，源码默认值为 `true`。部署前必须先创建 Generation 两张表并准备 RabbitMQ 延迟插件与 Redis；紧急回滚时显式设置为 `false`，恢复原同步 POST SSE 行为。

关键语义如下：

- SSE `CANCEL`、`ERROR` 或写出失败只提交 `DETACHED`，不直接取消模型或退款。
- 页面隐藏、站内切换和组件卸载不发送取消命令。
- 用户 Stop、管理员取消和同一 observer epoch 持续失联三十秒才写入 `CANCEL_REQUESTED`。
- Generation Worker 独立持有上游模型订阅，RabbitMQ Generation 消息由 Worker 完成后手动 ACK。
- Worker 通过 PostgreSQL 行锁和状态 CAS 冻结唯一终态；Rabbit Terminal 消息只负责唤醒，计费消费者会重新核对数据库终态事实。
- 正常完成沿用真实 Usage；客户端或管理员取消沿用既有真实 Usage、部分文本估算、无输出全退策略；上游或系统异常始终全额退款。
- PostgreSQL 与 RabbitMQ 没有 Outbox 或分布式事务，发布空窗由现有分钟级恢复任务有界补偿，不宣称 Exactly Once。
- 没有新增五百毫秒数据库轮询；实时主链路由 RabbitMQ 消息驱动。
- Redis 按一个 Generation 一个输出 Hash、一个会话一个上下文 Hash 保存，单个 Generation/会话内部暂不分片；这是明确接受 BigKey 风险的阶段性决策。

## 主要交付物

- `sql/011_create_ai_conversation_generation.sql`
- `sql/012_create_ai_conversation_generation_payload.sql`
- `sql/checks/ai_conversation_generation_orphans.sql`
- `ai-temperate-model` 中的 Generation 与 Payload 实体
- `ai-temperate-mapper` 中的 Generation/Payload Mapper、XML 与迁移契约测试
- `ai-temperate-service` 中的创建、Worker、Observer、取消、终态、Billing 和恢复 Service
- `ai-temperate-web` 中的 RabbitMQ 拓扑、Generation 查询/重连/取消接口和管理员取消接口
- `fornted/common/aichat/ai-conversation-generation-manager.js`
- `fornted/common/aichat/ai-conversation-stream.js`
- `fornted/components/user/workspace/user-chat-panel.vue`
- `docs/architecture/ai-conversation-async-generation.md`
- `docs/operations/ai-conversation-async-generation-rollout.md`

## 启用前必须提供的配置

- `AI_CONVERSATION_ASYNC_GENERATION_ENABLED=true`
- 每实例唯一的 `AI_CONVERSATION_INSTANCE_ID`
- RabbitMQ `x-delayed-message` 插件
- RabbitMQ consumer ACK timeout 大于模型最大运行时间至少五分钟
- HikariCP 与 PostgreSQL `max_connections` 按实例数、Worker 数和其他后台任务重新核算

可调参数包括三十秒失联宽限、一秒 Observer 心跳、Worker 并发、十五分钟 Worker 上限和二十四小时终态保留期；具体环境变量见发布文档。

## 当前验证状态

第二阶段隔离环境验证已经执行：

- `mvn -pl ai-temperate-web -am -DskipTests compile` 成功，六个 Maven 模块全部完成生产源码编译。
- Generation 数据库契约测试两项、RabbitMQ 配置契约测试五项、核心 Service 与 Web Controller 测试三十项全部通过。
- PostgreSQL 15 Testcontainers 迁移测试四项通过，覆盖两张表、禁止物理外键、活动任务唯一约束、`SKIP LOCKED` 和终态 CAS。
- Redis 7.4 Testcontainers 集成测试一项通过，覆盖单个 Generation Hash 中的快照、revision 和终态写入。
- `npm run test:chat` 共五十六项测试全部通过，包含异步 Generation 和全局 Manager 场景。
- HBuilderX 5.15 H5 构建成功，输出目录为 `fornted/unpackage/dist/build/web`；将已有 `public/_headers` 和 `public/_redirects` 补入本次产物后，H5 发布产物校验通过。当前发布命令尚未自动复制这两个无扩展名文件，正式发布流程必须保留显式复制步骤。
- `npm run test:release` 四项中三项通过，一项因既有 `common/auth/auth-api.js` 导入了 `password-policy.js` 未导出的 `classifyPassword` 和 `passwordError` 而失败，与本次 Generation 改造无关。
- 完整 `SecurityConfigurationTest` 仍有一个既有 CSRF Cookie `SameSite=Strict` 断言失败；该断言与本次 Generation 功能无关，新增 `X-AI-Generation-Id` CORS 暴露方法已经单独验证通过。

本次只连接了 Testcontainers 创建的临时 PostgreSQL、Redis 与 RabbitMQ，没有执行真实数据库迁移，也没有连接生产 PostgreSQL、Redis 或 RabbitMQ。RabbitMQ delayed-message 单节点可靠性、真实三十秒延迟和三节点 Quorum Leader 故障切换已经通过；Redis A/B Pub/Sub 快照恢复和 Owner 定向取消已经通过。真实预发布迁移、部署态 A/B 应用全链路、Android、外部 Chrome 浏览器端到端和 200 次 P95 时效目标仍不得标记为已通过。

静态一致性核对也已执行，包括新 YAML 注释邻接、迁移不含物理外键、Generation 源码不含五百毫秒调度、Rabbit 消息不包含资金指令、公共响应头跨域暴露和工作区文件可见性检查。

## 第二阶段剩余顺序

1. 在隔离预发布库演练两张表迁移及孤儿检查，功能开关保持关闭。
2. 启动两个隔离应用实例，使用假上游完成 Generation、Stop、失联和重复消息全链路。
3. 修复或确认 H5 发布脚本自动复制 `_headers` 和 `_redirects` 的方式，并处理既有前端 ESM 发布契约失败。
4. 执行 H5 与 Android 的页面切换、刷新重连、浏览器关闭、强杀、Stop 和管理员取消端到端验收。
5. 在隔离部署环境以 200 个样本统计取消与结算 P95。

所有第二阶段基础设施必须使用隔离测试环境，禁止连接生产 PostgreSQL、Redis 或 RabbitMQ。
