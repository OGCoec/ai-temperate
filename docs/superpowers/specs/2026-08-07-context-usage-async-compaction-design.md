# 上下文用量、异步压缩与按需 SSE 设计

## 目标

本变更为每个 AI 会话维护可重建的 Redis v2 Token 快照。前端只展示并触发压缩，后端负责用量估算、阈值判断、单飞调度和绝对容量校验。

阈值使用整数交叉相乘判断：

```text
estimatedContextTokens * 100 >= contextWindowTokens * 80
```

因此刚好 80% 会触发异步压缩。80% 不是发送门槛；只有“当前提示词 + 本次输入 + 模型最大输出”超过模型绝对窗口时，发送链路才等待压缩，最长 60 秒。

## 权威边界

- PostgreSQL 是已完成消息和持久摘要的权威数据源。
- Redis v2 是可重建的上下文派生快照，不新增数据库表。
- Provider usage 继续用于计费和观测，不直接作为当前会话上下文总量。
- 前端本地重算仅用于模型切换后的即时展示，REST/SSE 返回值会覆盖本地结果。
- 只有 `USER_STOP` 的部分回答进入未来提示词；传输断开、系统失败和管理员取消不增加上下文 Token。

## Redis 数据

所有 Key 由 `RedisKeyFactory` 生成，业务代码不得拼接：

- 会话上下文 Hash：v2 命名空间。
- 压缩任务 Hash：v1 命名空间，每个会话一个短期状态。
- 事件 revision：v1 命名空间，每个会话一个小型计数 Key，TTL 与上下文缓存一致，避免任务 Hash 过期后序号回退。
- 上下文事件：固定 v1 Pub/Sub 频道，只发送小型唤醒通知。

上下文 `meta` 包含 `schemaVersion`、`generation`、`estimatedContextTokens`、`contextRevision`、`updatedAt`、`lastCompactedAt`、`latestPersistedMessageId` 和持久压缩检查点。每个可进入提示词的 Turn 单独保存 Token 估算，压缩时按冻结截止点增量扣除。

旧 v1 Key 不解释为 v2。v2 未命中时，从 PostgreSQL 按有界分页重建持久消息尾部；Redis-only 中断草稿过期后不可恢复，这是既有派生缓存语义。

## 原子更新

Lua 脚本在同一 Redis 原子边界内更新内容、Token 总量和 `contextRevision`：

- 完成：持久 Turn 替换临时 Turn，增加持久 Turn Token。
- 用户停止：保存 `USER_STOP` 部分 Turn，增加该 Turn Token；Worker 启动前的零输出 Stop 也先创建用户 Turn。
- 其他中断：保存诊断状态，Token 增量为零。
- 压缩：删除冻结截止点以前的已选 Turn，扣除其 Token，再加入摘要 Token。

所有写入校验 `generation`；需要覆盖元数据的操作同时校验 `contextRevision`。并发冲突由调用方采用胜出快照有限重试，不进行逐条 Redis I/O 或无限循环。

## 异步压缩状态机

压缩状态为 `QUEUED -> RUNNING -> COMPLETED|FAILED`。触发来源包括模型切换、回答完成、用户停止、Hash 字段安全阈值和硬容量等待。

Redis claim 保证同一会话同一上下文版本只有一个任务。执行器使用固定小线程池和有界队列；队列拒绝时任务转为可重试失败。任务开始时冻结：

- `latestPersistedMessageId`；
- 当时已纳入提示词的最大临时 ordinal。

旧任务只能删除冻结截止点以前的内容。压缩期间的新回答继续追加到尾部。任务结束后如果发现新尾部且最新版本仍达到 80%，协调器为最新版本再声明一个单飞任务。

## 硬容量等待

正常达到 80% 只调度任务并继续发送。准备提示词抛出绝对容量错误时：

1. 声明或复用 `HARD_LIMIT_WAIT` 任务；
2. 订阅跨实例压缩事件并立即复查 Redis 状态；
3. 以 Reactor `Mono.timeout` 最多等待 60 秒，不轮询 Redis、不占用请求线程；
4. 成功后只重新准备一次提示词；
5. 第二次仍超限返回 `409 AI_CONTEXT_TOO_LARGE`，失败或超时返回相应 `503`。

不存在同步压缩回退路径。

## REST 与 SSE

- `GET .../context-usage` 返回指定模型窗口下的权威快照。
- `POST .../compactions` 重新读取后端快照；小于 80% 返回 `NOT_REQUIRED`，否则创建或复用任务。
- `GET .../context/events` 是按需 SSE，不是永久连接。

SSE 严格采用“先订阅 Pub/Sub，再做会话归属检查并读取快照”。初始化期间收到的通知先进入内存队列，快照发出后按 `eventRevision` 排序去重。连接每 10 秒心跳，最长 70 秒；终态事件自动关闭。Pub/Sub 丢失不会改变任务状态，重连仍从 Redis Hash 恢复。

## 前端行为

已有会话初始化后显示 `200K / 1M · 20%` 和进度条。模型切换时立即用相同 Token 总量和新模型窗口乐观重算，然后请求权威快照；权威结果达到 80% 时先打开 SSE，再 POST 压缩请求。

快速切换会关闭旧模型连接、保留会话级 `eventRevision`，并丢弃会话或模型不匹配的迟到事件。正常完成优先消费 completed 事件携带的 `contextUsage`。用户 Stop 同样先等待上下文 SSE 完成握手，再发送取消请求。上下文 API/SSE 不可用时隐藏用量，不改变发送门控。

## 可观测性

新增或复用以下低基数指标：

- 压缩排队次数和触发来源；
- 压缩任务耗时及成功、失败、队列拒绝结果；
- 硬容量等待耗时及成功、失败、超时结果；
- 上下文 SSE 当前连接数及打开/关闭次数；
- 上下文缓存命中、未命中、重建、损坏、冲突和不可用次数。

指标不包含用户 ID、会话 ID、模型 ID、完整 Redis Key 或 Token 内容。

## 配置默认值

```text
预压缩阈值                 80%
硬容量等待                 60s
SSE 心跳                   10s
SSE 单次最长连接           70s
运行中任务 TTL             10m
终态保留                   5m
```

## 验证阶段

第一阶段只交付实现和测试源代码，不运行测试、编译、打包或外部连接。第二阶段必须由用户单独批准，并使用隔离配置，不连接生产 PostgreSQL、Redis、RabbitMQ 或模型服务。
