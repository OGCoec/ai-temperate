# 019fade7-eae9-72c3-9208-057c793971a7：CLIProxyAPI 模型调用、SSE 与用量结算交接

## 1. 本文目的与术语校正

本文交接该任务后续最重要的后端能力：普通用户选择一个已启用的本地 AI 模型，经由 **CLIProxyAPI** 发起一次真实模型调用；服务端将上游 SSE 内容安全地转发给客户端；上游在流结束时提供的最终 Usage 用于额度结算，并在 PostgreSQL 留下可审计的调用、计费与会话记录。

用户此前口述的“ChillyPolicy API”在当前工程中对应 **CLIProxyAPI**。管理员模型发现仍使用 `GET /v1/models`；普通用户推理则由 Spring AI OpenAI SDK 客户端向同一网关发起单次流式调用。模型发现响应本身不提供 Token 用量，只有推理流最终 Usage 才参与结算。

用户此前口述的“AI compensation / AI compensation message”在仓库中的实际表名是：

```text
ai_conversation
ai_conversation_message
```

后续代码、接口、文档和测试必须使用仓库真实表名，不能新建名称相近的重复表。

## 2. 当前实现边界

### 已实现源码

- `ai_model` 已保存本地模型配置、启用状态和三种倍率：`input_ratio`、`cached_input_ratio`、`output_ratio`。
- 已启用模型配置可以由现有模型缓存读取；`cached_input_ratio` 是上游 Prompt Cache 命中输入的倍率，和本项目 Redis 缓存无关。
- SQL 007–010 已创建四张业务表及其索引、检查约束和孤儿数据检查 SQL。
- 管理员已有 CLIProxyAPI 模型发现接口；其地址和访问密钥只在服务端环境变量中配置，浏览器不能直接访问 `127.0.0.1:8317`，也不能接触密钥。
- 普通用户创建/继续会话的 POST SSE Controller、Spring AI 客户端、最终 Usage 解析和受控事件协议。
- PostgreSQL 行锁预扣、多退少补、超额不足转待对账、消息与 Usage 同事务持久化，以及提交后的用户资料缓存失效。
- Redis Hash 三天绝对 TTL 上下文、4 KiB/250 ms 流片批处理、中断临时轮次、generation CAS 和持久/临时两层压缩。
- Redis ZSET + Lua 的跨实例全局/单用户并发租约，以及过期 RESERVED 每批最多五百条的待对账扫描。

### 本次不负责

- 普通用户会话列表、历史消息分页和删除等前端产品接口。
- 工具调用、多代理循环和同一用户轮次内的多次上游模型 HTTP 请求。
- Redis 过期后的中断回答恢复；中断内容明确是可丢失的三天临时上下文。
- Worker 部署、真实 CLIProxyAPI 联调、数据库迁移和第二阶段测试执行。

本阶段只说明源码边界，尚未执行构建、测试或外部联调，不能据此宣称运行验证通过。前端仍不得直连 CLIProxyAPI。

## 3. 四张表的职责

| 表 | 一条记录代表什么 | 写入时机 | 不负责什么 |
| --- | --- | --- | --- |
| `ai_model_usage` | 一次已经成功预扣、并实际开始向上游发出的模型 HTTP/SSE 调用 | 预扣成功后、调用上游前 | 不保存请求原文、SSE 分片或模型回答全文 |
| `ai_model_usage_detail` | 对应一次用量的一对一计费证据 | 与 `ai_model_usage` 在同一预扣事务内创建 | 不作为会话历史或输出正文存储 |
| `ai_conversation` | 一个用户的一段连续 AI 会话 | 首次消息前创建或确认归属时 | 不存每条输入和回答 |
| `ai_conversation_message` | 一次完整的“用户输入 + 模型最终回答”配对 | SSE 已得到完整、可提交的最终回答后 | 不保存图片二进制，不保存流式中间片段 |

关联均为逻辑关联，项目禁止物理外键。写入前 Service 必须校验目标用户、模型、会话存在且属于当前用户；删除/软删除时执行各自的显式清理和孤儿检查。

### 3.1 `ai_model_usage`：最终用量和结算主记录

来源：[007_create_ai_model_usage.sql](../../sql/007_create_ai_model_usage.sql)。

- 主键 `id` 是固定 16 字节 Hybrid ID，也是服务端模型请求 ID。
- `billing_status` 用于记录预扣、已结算、已退款或需要对账的状态；预扣失败且没有向上游发出请求时，**不得**写入本表。
- 最终 Usage 中的 `prompt_tokens`、`completion_tokens`、`cached_prompt_tokens`、`reasoning_tokens` 在流结束后回填。上游未报告 Usage 时，不得伪造为 0。
- `charged_quota_minor` 保存最终实际扣除的项目额度最小单位；`settled_at` 只在完成最终结算时写入。
- `finish_reason` 与 `failure_code` 只保存受控枚举/错误码，不保存上游完整响应、异常堆栈、用户 Token 或 API Key。

### 3.2 `ai_model_usage_detail`：幂等和计费快照

来源：[008_create_ai_model_usage_detail.sql](../../sql/008_create_ai_model_usage_detail.sql)。

- `usage_id` 与 `ai_model_usage.id` 一对一；唯一约束禁止为同一次调用创建多条详情。
- `idempotency_key_digest` 是业务命名空间、当前用户内部 ID 与客户端 `Idempotency-Key` 的 HMAC-SHA256 摘要；不得保存原始幂等键。
- `vendor_snapshot`、`is_stream`、估算输入 Token、最大输出 Token、三种倍率和预扣额度都必须在发出上游请求前固化。
- 管理员之后修改模型倍率时，历史用量仍依赖 `input_ratio_snapshot`、`cached_input_ratio_snapshot`、`output_ratio_snapshot` 复算，不能回查今天的模型配置。
- `settlement_delta_minor = 实际扣费 - 预扣额度`：正数补扣，负数退款，`NULL` 表示尚未结算。

### 3.3 `ai_conversation`：会话主表与压缩边界

来源：[009_create_ai_conversation.sql](../../sql/009_create_ai_conversation.sql)。

- 一个用户可拥有多个会话；`is_active=false` 是软删除，不能物理删除会话记录。
- `title` 只在第一条成功消息含文字时生成；第一条只有附件时保持 `NULL`，前端显示“未命名对话”。
- `last_message_id` 只在完整消息、Usage 和额度结算同一事务成功时更新；会话侧栏按 `(last_message_id DESC, id DESC)` 游标分页，并排除没有持久消息的会话。
- `last_compacted_message_id` 与 `compacted_context` 必须同时为 `NULL` 或同时有值。压缩文本覆盖该消息及其之前的历史。
- 恢复上游上下文时，顺序固定为：`compacted_context`（若存在）→ `last_compacted_message_id` 之后的完整消息，不能重复发送已压缩消息。
- 会话 ID 是内部 16 字节 ID；对外路径和响应必须继续使用统一 Base64URL 公共 ID，Controller 不得自行编码。

### 3.4 `ai_conversation_message`：完整问答配对和搜索规则

来源：[010_create_ai_conversation_message.sql](../../sql/010_create_ai_conversation_message.sql)。

- `content_text` 保存用户原始文字；任意文件附件对象独立保存到 `content_attachments` JSONB 数组，二者可以同时存在。
- `content_parts` 保存文字输入的 Java IK 分词结果 JSON 数组，且仅在非空数组时进入 GIN 索引。
- `question_tokens` 这个历史字段名虽然容易误解，但当前 DDL 的实际语义是“模型针对本行输入返回的完整原始回答文本”；模型生成附件对象保存在 `response_attachments`。
- 文字与附件至少存在一项即可，因此支持纯附件输入和纯媒体输出。只有得到完整可提交回答后才原子写入一行；流中断、上游失败或客户端取消时不制造一条带伪回答的消息。

## 4. 输入、图片和 IK 分词不变量

### 文本输入

```text
用户原始普通提示
→ 校验长度、空白和允许的内容类型
→ 保存原文到 content_text
→ Java IK 分词
→ 分词数组保存到 content_parts
→ 原文和分词结果一起用于会话与搜索
```

- IK 分词只用于普通文本提示的检索，不得用于额度估算、权限判断或向上游拼接新的用户内容。
- 不得把用户输入拆词后再拼成不同文本发送给模型；发送给 CLIProxyAPI 的是受控的原始文本/结构化请求。
- `content_parts` 是 JSONB 字符串数组，不是全文检索的替代品；GIN 仅支持词元包含/存在查询，不保证词序和语义匹配。

### 通用附件输入

OSS 前缀、CORS、一天临时对象 Lifecycle 和 public-read 风险的部署要求见
`docs/operations/ai-conversation-attachments-oss.md`。

```text
客户端声明文件元数据并取得私有临时对象的预签名 PUT
→ 客户端直接上传 OSS
→ SSE 开始前由服务端 HEAD 校验对象、大小和 Content-Type
→ 完整回答结束后复制到正式 public-read 路径
→ content_attachments 保存稳定附件对象数组
→ content_text 仍保存同一提问中的原始文字
→ 只有 content_text 经 IK 分词写入 content_parts
→ 只有 IMAGE/AUDIO/VIDEO 且模型能力匹配时才进入模型，其余文件只随历史保存
```

- PostgreSQL 不保存文件二进制、Base64 或数据 URI，也不新增附件明细表。
- 文件名和 URL 不参与 IK 分词，不进入 `content_parts` GIN 索引，也不作为搜索关键词。
- 客户端不能提交 Bucket、Object Key、用户 ID 或最终 URL；所有对象路径均由服务端工厂生成。
- 不在日志、SSE 事件或异常中输出预签名 URL、完整公网 URL、Object Key、Token 或媒体内容。

## 5. 推荐的普通用户模型调用链

只实现 CLIProxyAPI 的 OpenAI 兼容 Chat Completions 协议：`POST /v1/chat/completions`，不调用 `/v1/responses`，也不另行增加所谓“New API”第二条推理通道。外部调用方只调用 AI Temperate 的普通用户 API；AI Temperate 才能调用 CLIProxyAPI。

```text
已认证用户请求模型
→ 解析并授权本地 ai_model 公共 ID
→ 校验模型已启用、会话归属、输入类型与幂等键
→ PostgreSQL 本地事务：锁定额度、必要时开启新周期、预扣、写 usage + usage_detail
→ 提交事务
→ 服务端调用 CLIProxyAPI 的 `POST /v1/chat/completions`，要求 stream=true
→ 逐条校验并转发安全 SSE 内容
→ 接收上游最终 Usage / [DONE]
→ PostgreSQL 本地事务：最终结算 usage + 额度余额
→ 仅在完整成功回答时写 conversation_message
→ 向客户端发送 completed 或 error 终态事件
```

### 5.1 调用前必须完成的事情

1. 从认证上下文取得内部用户 ID；绝不信任请求体传入的用户 ID。
2. 用本地模型公共 ID 查询模型并校验 `is_enabled=true`；客户端不得传倍率、厂商、上游地址或 API Key。
3. 校验或创建会话，并验证会话属于当前用户且 `is_active=true`。
4. 校验请求 `Idempotency-Key`。同一用户、同一业务命名空间和同一键只能创建一条用量详情；重复请求必须返回已有调用状态，绝不再次扣费或重复调用上游。
5. 在同一 PostgreSQL 本地事务内锁定 `user_membership_quota`；周期未开始或已到期时，先按现有惰性周期规则开启新周期，再做预扣。
6. 把当前模型的三种倍率、估算输入 Token、最大输出 Token和预扣额写入 `ai_model_usage_detail`；再写 `ai_model_usage` 的预扣中状态。
7. 事务提交之后才允许创建上游网络连接。外部 HTTP/SSE 读取禁止包在数据库事务内。

### 5.2 最终扣费公式

上游最终 Usage 到达后，先验证所有 Token 非负，并要求：

```text
cached_prompt_tokens <= prompt_tokens
```

然后按调用时快照计算：

```text
uncached_prompt_tokens = prompt_tokens - cached_prompt_tokens

actual_cost =
    uncached_prompt_tokens × input_ratio_snapshot
  + cached_prompt_tokens × cached_input_ratio_snapshot
  + completion_tokens × output_ratio_snapshot
```

- `reasoning_tokens` 只有在厂商没有把它包含在 `completion_tokens` 时才可单独计价；目前四表没有独立 reasoning 倍率，默认不允许重复计费。
- 货币/额度最小单位的换算和舍入只能由一个服务端 `QuotaCostCalculator` 负责，内部使用 `BigDecimal`，在最终得到 `charged_quota_minor` 时只舍入一次；不得使用 `double` 或浏览器计算。
- 预扣应按请求的估算输入和最大输出保守计算；结算后依据 `settlement_delta_minor` 执行补扣或退款。
- 上游没有提供可信最终 Usage、Usage 结构非法、或补扣无法原子完成时，将调用标记为 `RECONCILE_REQUIRED`，记录受控错误码并进入有限的后台对账流程；不得伪造 0 Token，也不得无限重试。

## 6. SSE 协议和终态语义

### 6.1 上游与客户端的责任

- 上游 CLIProxyAPI 的 SSE 帧是外部不可信输入。服务端必须限制单帧和累计内容大小、校验事件 JSON、忽略未知字段，并禁止把上游 Headers/错误正文直接透传给客户端。
- 下游客户端 SSE 是 AI Temperate 自己的稳定协议。内容分片可转发，但调用完成的依据必须是服务端接收并验证了上游最终 Usage、完成结算并持久化成功，而不是“最后一次文本分片看起来到了”。
- 上游中间累计 Usage 只能作为候选值；只有与明确终止 `finishReason` 同片，或在终止片之后到达的 Usage，才能提升为可信最终 Usage。终止片之前的候选不能因稍后看到终止信号而被直接提升，避免把中间累计值误当最终值。
- 单次调用只维护一个上游连接和一个下游连接；不能为每个 SSE 分片执行 Mapper、Redis 或结算 I/O。

### 6.2 下游终态约束

- 实际事件字段与顺序以第 11.3 节为准：`accepted → (delta | heartbeat)* → completed/error`。
- `completed` 只允许发送一次；它返回公共资源 ID、最终 Usage、最终扣费和结束原因，完整正文已经由此前有序 `delta` 提供并在同一结算事务中持久化。
- 对外的 BIGINT、Hybrid ID、额度和 Token 总数继续使用公共编码或十进制字符串，避免 JavaScript 精度丢失。
- `delta` 不能泄露上游 API Key、请求头、内部数据库 ID、Redis Key 或未脱敏诊断信息。
- 最终 Usage 和成功结算先取得终态时，即使客户端随后断开也继续持久化；取消先取得终态时则取消上游、不写消息表，并按可信 Usage 或待对账规则收敛。
- 上游超时、网络断开、异常事件和非 2xx 都走 `error` 终态；不能把部分输出包装成成功完成。

## 7. 事务、一致性与恢复

项目不使用分布式事务、Outbox 或 CDC，因此必须明确接受以下窗口：预扣提交成功后、上游调用结束前，应用可能崩溃。该窗口通过用量状态、幂等键、有限对账与额度恢复收敛，不得宣称 Exactly Once 或跨 PostgreSQL/CLIProxyAPI 原子。

推荐状态处理：

| 场景 | 用量记录 | 额度处理 |
| --- | --- | --- |
| 预扣不足，未调上游 | 不写 usage | 事务回滚/拒绝 |
| 上游调用已开始，流尚未结束 | `RESERVED` | 保留预扣 |
| 收到可信最终 Usage 且结算成功 | `SETTLED` | 最终扣费、补扣或退款 |
| 明确上游失败且未产生可计费输出 | `FAILED_REFUNDED` | 全额退款 |
| 客户端取消或上游未返回可信 Usage | `RECONCILE_REQUIRED` | 按受控策略冻结/退款，并由有限对账处理 |
| 已结算后业务补偿 | `REFUNDED` | 记录退款原因和实际余额变化 |

缓存只可用于已启用模型配置和用户页面展示，不能决定是否允许扣费。额度权威值永远是 PostgreSQL 行锁事务中的 `user_membership_quota`。

## 8. 安全边界

- CLIProxyAPI 的 `CLI_PROXY_API_KEY` 只从环境变量读取；不得写进 YAML 默认值、客户端、日志、数据库表或 SSE。
- 仅允许服务端配置的 CLIProxyAPI base URL 和固定兼容端点；请求体不能覆盖 host、path、Authorization 或模型倍率。
- 普通用户只能调用已启用模型，并且只能读取/写入自己的会话、用量和消息。
- 所有外部输入、上游 SSE、图片 URL 和模型输出都视为不可信数据。模型输出写入数据库前不执行、不拼接到 HTML/JavaScript、不作为 SQL 或日志格式字符串。
- 会话消息中的图片只保存 URL，密钥、一次性上传 URL、完整对象 Key 和模型 API 凭据不得保存到会话表。

## 9. 当前代码分层

- 领域对象和四张表 Mapper 位于 `model` 与 `mapper`；关联全部使用应用层校验和孤儿检查 SQL，不建立物理外键。
- 额度预扣、结算、中断收敛、上下文、并发和压缩均采用 `Service 接口 + Impl`，Controller 不包含计费或数据库编排。
- Spring AI 客户端只负责一次上游流式调用及最终 Usage 提取，不持有数据库事务。
- Redis Hash 是三天派生上下文，Redis ZSET 是跨实例并发租约；PostgreSQL 仍是完整消息和额度事实来源。
- 第一阶段已补齐对应测试源码，但尚未执行构建、测试、迁移或外部联调。

## 10. 后续测试清单（仅在用户明确授权第二阶段后执行）

- 幂等：重复 `Idempotency-Key` 只得到一个 usage、一次上游调用和一次余额变化。
- 并发：同一用户两次同时发起过期周期调用，只有一个事务能重置周期，余额不会双花。
- 预扣与结算：常规输入、缓存输入、输出、补扣、退款、舍入边界、余额不足。
- SSE：文本分片、最终 Usage、`[DONE]`、未知事件、超大事件、非法 JSON、上游中断、客户端取消和终态只能出现一次。
- 持久化：只有完整成功问答写 `ai_conversation_message`；失败/取消不会写伪回答；用量和详情一对一。
- 输入分类：普通文本生成 IK 词元；同一输入中的图片只保存 URL 且不参与分词，纯图片时 `content_parts=[]`，不保存图片二进制。
- 授权：跨用户会话/usage/message 访问返回受控 404/403；公开 ID 格式错误为 400。
- 运行时：CLIProxyAPI 不可用、401/403、超时与最终 Usage 缺失都产生受控错误和可观察指标，日志不泄露密钥或原始请求。

## 11. 当前普通用户 API 契约

### 11.1 首次发送与继续会话

新建页面本身不创建会话。首次真正发送时调用：

```http
POST /api/ai/conversations/responses
Accept: text/event-stream
Content-Type: application/json
Idempotency-Key: <UUIDv4>
```

继续已有会话时调用：

```http
POST /api/ai/conversations/{conversationPublicId}/responses
Accept: text/event-stream
Content-Type: application/json
Idempotency-Key: <UUIDv4>
```

- 首次发送由后端 `HybridSemaphoreIdWorker` 生成 16 字节会话 ID；前端不提交 UUIDv7 会话 ID。
- `conversationPublicId` 是该 Hybrid ID 的 22 字符规范 Base64URL 编码；不存在、已软删除或不属于当前用户统一返回受控错误。
- `Idempotency-Key` 只标识一次发送动作。服务端将用户 ID 与 UUIDv4 做用途隔离 HMAC 后写入详情表，原始值不落库、不写日志。
- 请求体不接受用户 ID、上游地址、API Key、倍率、上下文窗口或最大输出值。

实际请求体同时支持文字与最多八个已经预上传的受控附件引用：

```json
{
  "modelPublicId": "AAAAAAAAAAA",
  "input": {
    "text": "请说明这张图片中的代码",
    "attachments": [
      {
        "uploadSessionId": "AAAAAAAAAAAAAAAAAAAAAA",
        "attachmentId": "0123456789abcdefghijklmnopqrstuvwxyzAB",
        "fileName": "example.webp",
        "contentType": "image/webp",
        "sizeBytes": "428716"
      }
    ]
  }
}
```

最大输出由本地已启用 `ai_model.max_output_tokens` 决定，客户端不能覆盖。临时对象的模型读取地址由服务端生成短期签名 GET，Redis 与 PostgreSQL 均不保存签名 URL。

### 11.2 CLIProxyAPI 上游调用边界

业务层只依赖 `AiConversationModelClient`。其 Spring AI OpenAI SDK 实现在服务端固定 base URL 和 Bearer 密钥上创建一次流式请求，传入本地模型名称、服务端组装的历史消息和 `maxOutputTokens`，并要求最终 Usage。`contextWindowTokens` 只参与本地 80% 预算，不发送给 CLIProxyAPI；生成请求不自动重试。

### 11.3 下游 SSE 的实际事件顺序

```text
accepted → (delta | heartbeat)* → completed
accepted → (delta | heartbeat)* → error
```

`accepted` 只在预扣事务提交后产生：

```json
{
  "conversationPublicId": "<22字符公共ID>",
  "usagePublicId": "<22字符公共ID>",
  "modelPublicId": "<11字符公共ID>",
  "newConversation": true
}
```

`delta` 为 `{ "sequence": 1, "type": "text", "text": "..." }`；`heartbeat` 仅表示连接仍存活且不进入上下文或计费。`completed` 只有在可信最终 Usage、额度结算和消息事务全部提交后发送：

```json
{
  "conversationPublicId": "<22字符公共ID>",
  "messagePublicId": "<11字符公共ID>",
  "usagePublicId": "<22字符公共ID>",
  "promptTokens": "120",
  "cachedPromptTokens": "0",
  "completionTokens": "260",
  "reasoningTokens": "0",
  "chargedQuotaMinor": "48",
  "finishReason": "STOP"
}
```

`error` 为 `{ "code": "<受控错误码>", "retryable": false, "usagePublicId": "<公共ID>", "message": "<安全消息>" }`。POST SSE 不能依赖浏览器 EventSource 自动重连；网络重试必须复用原 `Idempotency-Key`。

## 12. 详细状态机、事务与四表写入规则

### 12.1 调用状态机

```text
REQUEST_VALIDATED
    ↓
QUOTA_LOCKED
    ↓
RESERVED (usage + usage_detail 已提交)
    ↓
UPSTREAM_CONNECTING
    ↓
STREAMING
    ↓
FINALIZING
    ├─ 成功 → SETTLED + message 已提交 → completed
    ├─ 明确未计费失败 → FAILED_REFUNDED → error
    └─ Usage 缺失/断连/最终事务失败 → RECONCILE_REQUIRED → error
```

数据库 `billing_status` 的合法转换固定为：

| 起始状态 | 允许目标状态 | 含义 |
| --- | --- | --- |
| `RESERVED (0)` | `SETTLED (1)` | 已取得可信 Usage 并完成最终扣费/退款 |
| `RESERVED (0)` | `FAILED_REFUNDED (2)` | 已确认上游未产生可计费调用，预扣已退回 |
| `RESERVED (0)` | `RECONCILE_REQUIRED (3)` | 无法安全确认最终费用，需要有限恢复任务处理 |
| `SETTLED (1)` | `REFUNDED (4)` | 已结算记录发生后续受控业务退款 |

禁止 `SETTLED → RESERVED`、`FAILED_REFUNDED → SETTLED` 或任意状态循环；这样后台扫描 `RESERVED/RECONCILE_REQUIRED` 时不会把已结束记录再次发送给上游。

### 12.2 预扣事务：必须一次提交完成

Service 的公开业务方法开启 PostgreSQL 本地事务，按下列顺序执行：

1. 解析当前用户 ID、模型内部 ID、会话内部 ID，完成授权与输入校验。
2. 查询 `user_membership_quota`：`WHERE login_identity_id = ? FOR UPDATE`。禁止根据 Redis 或页面余额判断可用额度。
3. 若 `quota_period_started_at IS NULL`、`quota_period_ends_at IS NULL` 或 `now >= quota_period_ends_at`，按既有惰性额度周期规则初始化新周期；再在同一把行锁下计算可预扣额度。
4. 用服务端 `QuotaCostCalculator` 基于“估算输入 Token + maxOutputTokens + 调用时三种倍率”计算保守 `reserved_quota_minor`。不得用 JavaScript、`double` 或客户端给出的价格。
5. 如果余额不足，回滚并返回 `AI_QUOTA_INSUFFICIENT`。此时没有 usage、没有 detail、没有上游请求。
6. 生成 16 字节 usage Hybrid ID；插入 `ai_model_usage`：用户、模型、`billing_status=RESERVED`、其余最终 Usage/费用字段为 `NULL`。
7. 对幂等摘要执行唯一插入 `ai_model_usage_detail`。写入厂商、`is_stream=true`、估算输入、最大输出、三种倍率快照和预扣额。
8. 扣减 `user_membership_quota.quota_balance_minor`，校验影响行数恰好为 1；提交事务。

幂等冲突处理必须先读取已有 `usage_detail.usage_id`，再查询对应 usage 状态：

- 已 `SETTLED`：返回已完成的受控查询结果，不能再次 SSE 调用上游。
- 仍 `RESERVED`：返回 `409 AI_INVOCATION_IN_PROGRESS` 或受控状态事件，不能再次扣费。
- `FAILED_REFUNDED` / `RECONCILE_REQUIRED`：返回原终态；是否允许用户以**新**幂等键重新提交通常由前端显式决定。

### 12.3 四表的逐字段写入矩阵

| 表/字段 | 预扣提交时 | 流结束成功时 | 失败/取消时 |
| --- | --- | --- | --- |
| `ai_model_usage.id` | 新生成 Hybrid ID | 不变 | 不变 |
| `login_identity_id`、`ai_model_id` | 从认证上下文/本地模型写入 | 不变 | 不变 |
| `billing_status` | `RESERVED` | `SETTLED` | `FAILED_REFUNDED` 或 `RECONCILE_REQUIRED` |
| `prompt_tokens`、`completion_tokens`、`cached_prompt_tokens`、`reasoning_tokens` | `NULL` | 由最终 Usage 回填并校验 | 无可信 Usage 时保持 `NULL` |
| `charged_quota_minor`、`settled_at` | `NULL` | 写入最终费用和服务端 UTC 结算时刻 | 退款完成可记录 0；待对账保持 `NULL` |
| `finish_reason`、`failure_code` | `NULL` | `STOP`/`LENGTH` 等受控完成值 | `UPSTREAM_ERROR`、`CLIENT_CANCELLED` 等受控值 |
| `ai_model_usage_detail.*snapshot` | 一次写全 | 不覆盖快照 | 不覆盖快照 |
| `upstream_request_id` | 若连接建立后拿到则受控更新 | 保持 | 有则保存，不泄露给客户端 |
| `settlement_delta_minor` | `NULL` | `actual - reserved` | 退款/待对账按状态规则写入 |
| `ai_conversation` | 只校验/按需创建 | 可更新压缩状态，但不因分片更新 | 不删除 |
| `ai_conversation_message` | 不写 | 在最终事务中一次写入完整问答 | 不写伪回答 |

### 12.4 最终结算事务

上游 `DONE` 标记本身不代表成功；Service 必须已经取得并验证最终 Usage。之后进入一个新的、短小的 PostgreSQL 本地事务：

1. 再次锁定同一用户的额度行，避免同时结束的另一次调用把余额覆盖。
2. 用 detail 中的快照而不是当前 `ai_model` 表计算 `actual_cost`，得到 `settlement_delta_minor`。
3. 正数差额执行补扣：余额不足时不能悄悄变为成功，标记 `RECONCILE_REQUIRED`；负数差额退款；零差额不改余额。
4. 更新 usage 的最终 Token、费用、结束原因、状态和结算时间，并更新 detail 的差额。
5. 对文本输入做 IK 分词并插入 `ai_conversation_message`：`content_text=原文`、`content_parts=词元数组`、`question_tokens=完整模型回答`。
6. 文字与附件可以同时存在：用户原文仍写入 `content_text`，正式附件对象数组写入 `content_attachments`；`content_parts` 只保存原文的 IK 词元。助手文字写入 `question_tokens`，模型生成附件对象数组写入 `response_attachments`，文件名和 URL 均不参与分词。
7. 同一事务更新 `ai_conversation.last_message_id`，并在首条成功文字消息上按规则生成标题；所有上述写入提交成功后才发送 `completed`。

如果最终事务回滚，不能发送 `completed`。服务端应在单独的、有限的恢复事务中把 usage 标记成 `RECONCILE_REQUIRED`，记录受控 failure code，并由后台根据 `idx_ai_model_usage_pending_created_id` 扫描；不能无限重放上游请求。

### 12.5 上游 SSE、Redis 临时上下文与取消规则

- 每个 `delta` 立即转发并追加到单次请求范围的有界内存；Redis 写入按 UTF-8 4096 bytes 或 250 ms 中先到者批量刷新，禁止逐分片写 PostgreSQL。
- Redis Hash 的三天期限从创建或真实重建时确定，追加流片不得续期。单字段不超过 4096 bytes，单次 Lua/Pipeline 参数不超过 1 MiB。
- 数据库回源重建后的 Hash 如果超过单次 Redis 请求预算，先写入带五分钟绝对期限的随机临时 Hash，再通过 generation、字段上限和目标 Key 不存在校验原子 `RENAME` 为正式快照；临时 Key 过期后禁止被后续批次重新创建。
- 超过服务端固定的单次输出字节上限时取消上游连接，并按是否已有可信 Usage 进入中断结算或待对账；该边界不能由请求覆盖。
- 客户端连接关闭先抢到中断终态时取消上游订阅，不写消息表；已收到的部分回答原子标记为 `INTERRUPTED`，在 Redis 未过期期间允许进入后续上下文。
- 最终 Usage 已先抢到成功终态时，后续客户端断开不能取消结算 Future；消息与 Usage 提交成功后才形成完成结果。
- 中断内容不进入 PostgreSQL；Redis 过期后不可恢复。没有可信 Usage 时不得假设输出为 0 Token，而是按受控规则退款或转 `RECONCILE_REQUIRED`。
- 普通用户重新打开会话时，页面只调用 PostgreSQL 历史接口；Redis 中尚未过期的中断回答不合并、不展示，只可能在后续模型上下文中发挥作用。
- 上游 SSE 解析器只接受预期 JSON 结构和文本增量；心跳、空行和未知事件不写库。解析失败属于受控上游协议错误，不把原始行回显给客户端。

## 13. 推荐的 Java、Mapper 与 Web 分层文件清单

下列是当前源码已经采用的主要分层边界；职责不得合并到 Controller 或现有管理员模型发现 Service。

### 13.1 model 模块

```text
model/ai/entity/AiModelUsage.java
model/ai/entity/AiModelUsageDetail.java
model/ai/entity/AiConversation.java
model/ai/entity/AiConversationMessage.java
model/ai/enums/AiModelBillingStatus.java
```

- Entity 只表示数据库字段，不承担 Token 计算、SSE 解析或认证。
- `AiModelBillingStatus` 固定映射数据库状态约束，禁止在业务代码散落数字常量。
- 所有新文件在主要类型前写中文 JavaDoc，说明职责、逻辑关联和不负责的边界。

### 13.2 mapper 模块

```text
mapper/ai/AiModelUsageMapper.java
mapper/ai/AiModelUsageDetailMapper.java
mapper/ai/AiConversationMapper.java
mapper/ai/AiConversationMessageMapper.java
resources/mapper/ai/AiModelUsageMapper.xml
resources/mapper/ai/AiModelUsageDetailMapper.xml
resources/mapper/ai/AiConversationMapper.xml
resources/mapper/ai/AiConversationMessageMapper.xml
```

必须具备的 Mapper 操作：

- 用量/详情创建、按 usage ID 查询、按幂等摘要查询、最终状态条件更新。
- 额度表按 `login_identity_id` 的 `FOR UPDATE` 查询与受影响行数检查更新；不能在循环中逐条查询。
- 会话按内部 ID + 用户 ID 查询有效会话，创建会话，软删除会话，按游标分页读取。
- 消息按会话与自增 `id` 升序批量读取；完整成功问答单条插入；文本词元使用 JSONB 参数，不拼接 JSON 字符串。
- 允许批量读取历史消息和待对账 usage；任何集合查询必须一条批量 SQL 或按 500 条以内的批次执行。

### 13.3 service 模块

```text
service/user/aiconversation/response/AiConversationResponseService.java
service/user/aiconversation/billing/AiConversationBillingService.java
service/user/aiconversation/billing/AiConversationSettlementService.java
service/user/aiconversation/context/AiConversationContextService.java
service/user/aiconversation/compaction/AiConversationCompactionService.java
service/user/aiconversation/concurrency/AiConversationConcurrencyService.java
service/user/aiconversation/model/AiConversationModelClient.java
```

职责拆分：

- `AiConversationResponseService` 只编排“验证 → 预扣 → 上游流 → 终结算 → SSE 终态”，依赖接口而非 Impl。
- Billing 和 Settlement 是分开的 Spring Bean，避免同类 `this.method()` 绕过事务代理。
- `AiConversationModelClient` 由 Spring AI OpenAI SDK 实现，只负责固定上游请求、流读取和受控异常分类，绝不做额度写入。
- IK Tokenizer 只处理 `content_text`；图片 URL 不进入词元数组。

### 13.4 web 模块

```text
web/user/aiconversation/controller/AiConversationResponseController.java
web/user/aiconversation/api/AiConversationResponseRequest.java
web/user/aiconversation/api/AiConversationInputRequest.java
web/user/aiconversation/api/AiConversationExceptionHandler.java
```

- Controller 只处理 HTTP 输入、认证上下文、Reactor SSE 响应编排和响应头；禁止在 Controller 中编排 SQL、预扣、倍率、IK 分词或上游请求 JSON。
- 每个公开 Controller 需有中文 `@Tag` 和每个方法的中文 `@Operation`；敏感字段不出现在 OpenAPI 示例中。
- SSE 配置必须限制异步超时、并发连接和响应缓冲；超时只产生受控错误事件。

### 13.5 配置与运维文件

```text
ai-temperate-web/src/main/resources/application.yml
sql/checks/ai_model_usage_orphans.sql
sql/checks/ai_model_usage_detail_orphans.sql
sql/checks/ai_conversation_orphans.sql
sql/checks/ai_conversation_message_orphans.sql
docs/database/ai-model-usage-and-conversation-logical-relationships.md
```

新增 YAML 必须逐行紧邻中文注释，并通过环境变量提供 base URL、SDK 总超时、首字节超时、最大流时长、并发上限和恢复扫描批次。Spring AI SDK 当前没有独立兑现项目旧 `connect-timeout` 的稳定边界，因此该无效配置已删除。真实 CLIProxyAPI Key 不得有默认值。运维文档必须列出四份现有孤儿检查 SQL 的运行频率、正常结果为空集以及发现孤儿后的人工恢复步骤。

## 14. 错误码、可观测性和上线检查

### 14.1 推荐错误码

| 错误码 | HTTP / SSE 终态 | 是否可重试 | 说明 |
| --- | --- | --- | --- |
| `AI_INPUT_INVALID` | 400 | 否 | 文本为空、长度超限、输入类型非法或混合输入 |
| `AI_IMAGE_URL_INVALID` | 400 | 否 | 图片不是受控稳定 URL 或对象状态未验证 |
| `AI_MODEL_NOT_FOUND` | 404 | 否 | 模型公共 ID 不存在 |
| `AI_MODEL_NOT_ENABLED` | 409 | 否 | 模型存在但未启用 |
| `AI_MODEL_CAPABILITY_UNSUPPORTED` | 422 | 否 | 所选模型不支持图片或请求类型 |
| `AI_CONVERSATION_NOT_FOUND` | 404 | 否 | 会话不存在、软删除或不属于当前用户 |
| `AI_QUOTA_INSUFFICIENT` | 409 | 否 | 行锁事务中的可用额度不足 |
| `AI_INVOCATION_IN_PROGRESS` | 409 | 是，轮询同一调用状态 | 同一幂等键已经有 RESERVED 调用 |
| `CLI_PROXY_UNAVAILABLE` | 503 / `error` | 是，使用新幂等键 | 无法连接上游 |
| `CLI_PROXY_TIMEOUT` | 504 / `error` | 是，使用新幂等键 | 上游连接或首字节/读取超时 |
| `CLI_PROXY_STREAM_INVALID` | 502 / `error` | 否 | 上游 SSE 数据结构不可信 |
| `AI_USAGE_MISSING` | 502 / `error` | 否 | 结束时没有可信 Usage，进入对账 |
| `AI_SETTLEMENT_RECONCILE_REQUIRED` | 503 / `error` | 否 | 最终结算不能安全完成 |

是否允许“使用新幂等键重试”必须由前端显式的新用户操作触发，后台不会自动重放模型生成请求。

### 14.2 指标与日志

每次调用都记录低基数指标：固定请求结果、首字节耗时、总耗时、并发拒绝、上下文命中/回源、持久/临时压缩结果、预扣/补扣/退款/待对账以及 Redis 流片批次。标签只允许代码内白名单固定枚举，例如 `outcome`、`layer`、`operation`；禁止用模型名称、厂商、用户 ID、会话 ID、错误消息、完整 Redis Key、提示文本或上游请求 ID 作标签。

结构化日志至少包含 `traceId`、受控 `usageId`、固定结果码和耗时。日志禁止包含原始提示、完整回答、图片 URL、Cookie、Authorization、API Key、原始幂等键和上游原始错误正文。

### 14.3 部署前检查

1. Cloudflare Worker 普通用户 Host 的路径白名单要在发布前只增加本交接定义的 `/api/ai/**`，不可放宽到任意 `/api/**`。
2. CLIProxyAPI 只监听 `127.0.0.1` 或等价受控内网地址；AI Temperate 通过服务端环境变量读取 `CLI_PROXY_API_KEY`。
3. 检查所选模型在本地 `ai_model` 中已启用、倍率非负、能力声明与文本/图片输入一致。
4. 运行隔离环境的四份孤儿检查 SQL；正常结果均为空集。
5. 进入第二阶段前，先用模拟 CLIProxyAPI SSE 服务器覆盖所有终态；真实 CLIProxyAPI 联调必须单独得到用户授权，且不能连接生产额度数据。

## 15. 交接结论

后续实现的主线必须是：

```text
本地已启用模型配置
→ PostgreSQL 额度预扣 + usage/usage_detail 快照
→ CLIProxyAPI SSE 推理
→ 最终 Usage 结算
→ 完整问答写入 conversation/conversation_message
→ completed SSE 返回公共资源 ID、最终 Usage 和最终扣费
```

图片只保存受控 URL、不建图片数据库、不做 IK 分词；普通文本提示保存原文并使用 Java IK 分词写入 `content_parts`。所有扣费和终态判断在后端完成，前端只消费 AI Temperate 的受控 SSE。
