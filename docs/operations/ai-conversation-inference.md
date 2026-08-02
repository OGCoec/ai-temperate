# 普通用户 AI 会话推理运维说明

## 推理基础设施配置

普通用户推理默认关闭。部署环境必须显式设置以下变量后再重启后端：

```text
AI_INFERENCE_CLI_PROXY_ENABLED=true
AI_INFERENCE_SPRING_CHAT_MODEL=openai-sdk
AI_INFERENCE_CLI_PROXY_BASE_URL=http://127.0.0.1:8317/v1
CLI_PROXY_API_KEY=<由 Secret 管理服务注入>
AI_INFERENCE_MAX_STREAM_DURATION=15m
```

`CLI_PROXY_API_KEY` 禁止写入仓库、日志、数据库或浏览器响应。CLIProxyAPI 应只监听本机或受控内网地址。

`AI_CONVERSATION_COMPACTION_MODEL` 已取消。旧部署环境即使仍保留该变量，应用也不会读取；完成发布后应从部署清单中删除，避免误导运维人员。

`AI_INFERENCE_FIRST_BYTE_TIMEOUT` 同样已取消。深度推理可能长时间没有首个文本片段，服务端只保留从上游订阅开始计算的十五分钟总时限；每十五秒发送的下游 SSE 心跳不会重置该总时限。

## SSE 与 Redis 写入边界

上游模型片段到达后立即转换为下游 `delta`，不再等待 Redis 聚合窗口。相同文本在请求级内存中按 `AI_CONVERSATION_STREAM_FLUSH_INTERVAL`（默认 250ms）或 `AI_CONVERSATION_STREAM_FLUSH_BYTES`（默认 4096 bytes）批量写入 Redis；成功、失败和取消终态都会刷新剩余批次。

流式期间不逐片写 PostgreSQL。PostgreSQL 只处理请求开始时的预扣，以及成功、退款、客户端取消估算或待对账终态。前端的平滑逐字展示只调整渲染节奏，不修改真实 SSE、Usage 或持久化文本。

## 失败退款与客户端取消

- 上游超时、限流、鉴权失败、5xx、连接失败、流中断或最终 Usage 缺失属于系统失败；即使已经向用户展示部分临时文本，也全额退回预扣。
- 用户主动取消且没有任何输出时全额退款。
- 用户主动取消且已有输出、但没有最终 Usage 时，按预估输入和已展示输出的 UTF-8 保守 Token 估算结算并退回差额。
- PostgreSQL 持续不可用等无法确认状态的情况才进入 `RECONCILE_REQUIRED`；服务端不得在退款事务提交前发送对应 SSE `error`。

历史系统失败自动补退由以下开关控制，默认必须保持关闭：

```text
AI_CONVERSATION_HISTORICAL_SYSTEM_FAILURE_AUTO_REFUND_ENABLED=false
```

启用前应只读核对候选数量和金额，条件必须与应用白名单一致：`RECONCILE_REQUIRED`、没有正式消息、没有结算差额、扣费为空或零，并且失败码属于 `AI_UPSTREAM_TIMEOUT`、`AI_UPSTREAM_STREAM_FAILED`、`AI_UPSTREAM_UNAVAILABLE`、`AI_USAGE_UNAVAILABLE` 或 `AI_STREAM_TERMINATED_WITHOUT_USAGE`。确认后将开关改为 `true` 并重启后端；调度器每分钟最多处理五百条，使用 `FOR UPDATE SKIP LOCKED` 和批量 SQL，重复执行不会再次退款。`CLIENT_CANCELLED*` 与未知 `AI_RESERVED_EXPIRED` 不自动补退。

## 会话压缩模型来源

会话压缩候选集合直接来自管理员当前启用模型的加密聚合快照，每个候选只使用内部模型 ID 和 `modelName`。管理员启停事务提交后会刷新快照；缓存缺失或损坏时由现有模型目录一次批量回源 PostgreSQL。

系统在每次压缩任务开始时，通过会话公开 ID 与内部模型 ID 执行 Rendezvous 一致性哈希，冻结一个模型供该任务全部摘要页面使用。该机制不使用轮询、随机游标或跨模型自动重试；管理员变更启用集合只影响后续新任务。

“管理员启用”同时表示该模型能够在 CLIProxyAPI 中接收纯文本压缩请求。系统不会按照能力、标签、厂商或套餐二次过滤；无法完成文本压缩的模型必须由管理员修复或停用。

## 故障行为

- 没有任何管理员启用模型时，压缩选择返回 `AI_UPSTREAM_UNAVAILABLE`。
- Redis 快照不可用时，目录读取降级为 PostgreSQL 批量回源，不执行逐模型查询。
- 选中模型超时或上游失败时，本次压缩失败，不自动改用其他模型。
- 异步压缩属于可重建派生数据，失败不得覆盖已提交消息或结算结果；同步紧急压缩失败继续使用现有受控错误路径。

上线后应观察压缩成功、失败和跳过指标，以及模型快照读取、回源和刷新失败日志。指标标签禁止包含模型名称、会话 ID、用户 ID 或其他高基数值。
