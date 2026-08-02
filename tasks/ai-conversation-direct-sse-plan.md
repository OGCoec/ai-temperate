# AI 会话直连 SSE 与终态持久化实施计划

## 目标

让普通用户在模型生成期间直接收到小粒度 SSE 文本，实时输出不再经过 Redis Pub/Sub Observer；Redis 只承担取消/完成前后的临时缓存职责，正式历史消息只在模型正常完成并取得最终 Usage 后写入数据库。

## 现状判断

当前 `AI_CONVERSATION_ASYNC_GENERATION_ENABLED=true` 时，`/responses` 先进入 Generation Worker。Worker 按 `stream-flush-interval` 和字节阈值批处理，再调用 `String.join("", chunks)` 写入 Redis 输出，Observer 收到 Redis 事件后才转发浏览器。因此当前异步路径的外部 delta 是批次级别。

项目的同步 `AiConversationResponseServiceImpl` 已经具备“先向下游转发 chunk，再执行 Redis 批量持久化”的基础能力。第一轮不迁移整个 Web 模块到 WebFlux；保留 Spring MVC + Servlet 异步响应，先让 SSE 使用直接 Flux 路径。这样可以获得直连流式效果，同时避免影响普通 Controller、Security、Springdoc 和文件上传链路。

## 架构决策

1. 浏览器实时输出和缓存持久化分离；Redis 写入不能成为 SSE 输出的前置条件。
2. 普通完成才写正式 assistant 历史消息；用户 Stop 的部分回答只进入短期 Redis 草稿，不进入历史消息查询结果。
3. Stop 仍必须保留最小 Generation/结算终态记录，以保证退款、幂等和资源释放；“不写数据库”特指不写正式会话历史正文。
4. 浏览器侧可以表现为 `stream().content()` 的直连文本流，但后端内部继续保留 `ChatResponse` 的 Usage、finish reason 和 request ID 信息。
5. 只有明确用户 Stop 才标记取消；普通浏览器断开不能直接等同于用户 Stop，否则会破坏断线恢复语义。

## 实施任务

### Task 1：切换普通用户到直接 SSE 路径

**目标：** 让 `/responses` 不再由 Redis Observer 作为普通用户的实时输出出口。

**主要文件：**

- `ai-temperate-web/src/main/resources/application.yml`
- `ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/controller/AiConversationResponseController.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java`

**验收标准：**

- 普通请求走直接响应 Flux；每个可展示文本 chunk 可以在模型流结束前到达浏览器。
- Redis Pub/Sub Observer 不再决定普通用户 delta 的发送时刻。
- 不丢失 Usage、finish reason、request ID、媒体结果和计费终态。

### Task 2：将 Redis 改为旁路草稿/完成缓存

**目标：** 保留缓存能力，但不让缓存写入阻塞或合并浏览器 SSE。

**主要文件：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/AiConversationStreamBatcher.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/context/impl/RedisAiConversationContextStore.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/observer/impl/RedisAiConversationGenerationOutputStoreImpl.java`

**验收标准：**

- 浏览器事件不等待 `appendDelta`、Pub/Sub 或 Redis 批次完成。
- 正常完成时缓存得到完整回答；缓存失败不会把已经发送的文本重新变成批量爆发。
- 如果仍保留异步 Generation，实时输出事件和上下文批量持久化必须使用不同分支。

### Task 3：实现 Stop 与取消终态

**目标：** 用户主动 Stop 时停止上游，并把部分回答作为临时草稿保存，不写正式历史正文。

**主要文件：**

- `ai-temperate-web/src/main/java/com/example/temperate/web/user/aiconversation/controller/AiConversationGenerationController.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/cancellation/impl/AiConversationGenerationCancellationServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/impl/AiConversationGenerationTerminalServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationTerminalBillingPolicyImpl.java`

**验收标准：**

- Stop 会取消上游订阅并产生唯一的 `CLIENT_CANCELLED` 终态。
- 已生成文本写入短期 Redis 草稿；assistant 正式历史消息不插入。
- 仍然完成必要的额度、退款、幂等和租约释放记录。
- 正常完成与 Stop 并发时，只有先取得终态所有权的一方生效。

### Task 4：固定历史查询只读正式数据库

**目标：** Redis 草稿不进入历史会话列表和消息详情。

**主要文件：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/history/impl/AiConversationHistoryServiceImpl.java`
- `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/ai/AiConversationMessageMapper.java`
- 对应 MyBatis XML 或 SQL 文件
- `fornted/common/aichat/ai-conversation-store.js`
- `fornted/components/user/workspace/user-chat-panel.vue`

**验收标准：**

- 历史接口只查询 PostgreSQL 正式消息。
- Stop 的临时回答不会在刷新历史页面后出现。
- 正常完成的回答能在历史列表和详情中出现。

### Task 5：调整前端 Stop 和 SSE 展示

**目标：** 浏览器只负责即时渲染 SSE，并把明确 Stop 与普通断线区分开。

**主要文件：**

- `fornted/common/aichat/ai-conversation-stream.js`
- `fornted/common/aichat/ai-conversation-sse-h5.js`
- `fornted/components/user/workspace/user-chat-panel.vue`
- `fornted/common/aichat/ai-conversation-generation-manager.js`

**验收标准：**

- 文本 chunk 到达后立即进入当前回答，不等待完整回答。
- 用户 Stop 发送取消请求并等待后端终态，不把 Abort 当作成功完成。
- 正常 completed 才触发前端正式保存/刷新历史；cancelled 只显示当前页的停止状态。

## 不在第一轮修改的内容

- 不升级 Spring Boot 或 Spring AI。
- 不把整个 `ai-temperate-web` 从 MVC 迁移到 WebFlux。
- 不修改 Cloudflare Worker、公共 SSE 事件名称或 Redis Key 协议。
- 不把 `.stream().chatResponse()` 简单替换成只返回文本的 `.content()`，避免丢失计费终态信息。

## 主要风险

| 风险 | 影响 | 缓解方式 |
|---|---|---|
| 只在 Stop/完成时写缓存 | 服务器崩溃会丢失未刷新的部分回答 | 明确接受该取舍，或保留独立低频 checkpoint |
| 浏览器断开被误判为 Stop | 破坏断线恢复 | 只有显式 Stop 请求才能标记 CLIENT_CANCELLED |
| SSE 发送与 Redis 写入共用订阅 | 重新引入背压和卡顿 | 使用独立旁路、有限队列和终态补偿 |
| 完成与取消竞态 | 重复写历史或重复退款 | 使用现有终态所有权状态机和幂等键 |

## 分阶段验证

1. 先关闭异步 Generation，验证直连 SSE 是否在上游完成前持续产生多个浏览器事件。
2. 复现正常完成，确认 DB 有一条正式 assistant 历史消息。
3. 生成中点击 Stop，确认 Redis 有部分草稿、DB 没有 assistant 历史正文。
4. 刷新历史页面，确认 Stop 草稿不显示，正常完成记录显示。
5. 最后再决定是否保留异步 Generation 的断线恢复能力，并为它实现独立实时输出分支。
