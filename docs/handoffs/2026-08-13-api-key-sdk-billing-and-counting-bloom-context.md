# API Key、SDK 计费与计数布隆过滤器上下文交接

## 1. 文档元信息

- 交接日期：2026-08-13。
- 项目路径：`C:\Users\damn\Desktop\ai-temperate-main`。
- 当前 Git 分支：`main`。
- 当前 HEAD：`12efeb8`。
- 当前 `origin/main` 同样指向 `12efeb8`。
- 本文用于压缩当前超长会话上下文。
- 本文不是已完成实现的声明。
- 本文把“用户已经批准的设计”与“当前源码事实”分开记录。
- 后续代理必须先核对源码，再根据本文继续。
- 后续代理不得把讨论过的方案当成已经落地的代码。
- 本轮只创建交接文档。
- 本轮没有运行测试。
- 本轮没有运行编译。
- 本轮没有运行打包。
- 本轮没有执行数据库迁移。
- 本轮没有连接 PostgreSQL。
- 本轮没有连接 Redis。
- 本轮没有连接 RabbitMQ。
- 本轮没有连接本地 `8317` 上游。

## 2. 最重要的当前事实

- 当前工作区直接位于 `main`，不是一个干净隔离的功能工作树。
- 当前工作区存在大量用户自己的未提交修改。
- 这些修改涉及语音、前端、Android、配置和本地诊断工具。
- 不得清理、覆盖、回滚或整理这些无关修改。
- 不得执行 `git reset --hard`。
- 不得执行会覆盖用户文件的 checkout 操作。
- 不得因为 API Key 工作而修改现有语音和前端文件。
- API Key 数据库设计文件目前是未跟踪文件。
- API Key 的生产 Java 链路尚未实现。
- API Key Controller 尚未实现。
- API Key Service 尚未实现。
- API Key Mapper 尚未实现。
- API Key Entity/DO 尚未实现。
- API Key Redis 缓存尚未实现。
- API Key 计数布隆接入尚未实现。
- API Key 到 `8317` 的公开调用链路尚未实现。
- 目前已经有四个 API Key 相关建表 SQL 草案。
- 目前已经有四个相关孤儿检查 SQL。
- 目前已经有 API Usage 持久化契约测试源码。
- 该契约测试在本上下文中没有执行证据。
- 当前计数布隆源码仍然是旧实现。
- 当前身份布隆仍然有专用 Redis Store。
- 当前身份布隆仍然使用动态 Generation。
- 当前源码仍然会出现 `v1-g...`。
- “统一固定 v1 并启动清空重建”的计划尚未落地。
- 后续代理绝对不能误报该布隆改造已经完成。

## 3. 用户最终业务目标

- 用户不仅需要 H5 和 Android 网页客户端。
- 用户还需要向外部 Agent 提供 API Key。
- 外部 Agent 不需要先登录网页版。
- 外部 Agent 使用 API Key 直接调用模型。
- API Key 最终扣减其所属账号的同一份余额。
- H5、Android 与 API Key 必须看到一致的余额结果。
- API Key 可以只授权一部分 AI 模型。
- 用户可以创建多个 API Key。
- 一个用户与多个 API Key 是一对多关系。
- 一个 API Key 与多个 AI 模型是多对多关系。
- 多对多关系必须使用规范化映射表。
- 禁止把允许模型列表塞进一个 JSON、数组或逗号字符串字段。
- 每一次外部 HTTP 模型请求形成一条独立 Usage。
- API Key Usage 不保存用户提问正文。
- API Key Usage 不保存模型回答正文。
- API Key Usage 不创建网页版会话。
- API Key Usage 不创建网页版消息。
- API Key Usage 与网页版 Usage 使用不同的表。
- API Key Usage 仍然复用网页版的余额与计费计算原则。
- API Key 调用以 SSE 流式传输为主要场景。
- 后端上游是本机 `127.0.0.1:8317` 的 CLIProxyAPI/Chili-Pod 类服务。
- 对外接口希望兼容主流 OpenAI 风格调用方式。
- 不同供应商协议适配应采用接口加多个实现类。
- 禁止在业务流程中用长串 `if/else` 或 `switch` 选择供应商策略。

## 4. API Key 原文生成决策

- API Key 使用密码学安全随机数生成器。
- 用户明确倾向使用 64 字节随机载荷。
- 不采用 32 字节随机载荷作为本项目最终偏好。
- 64 字节随机值已经远超实际暴力破解需要。
- 随机载荷使用 Base64URL without padding 编码。
- Base64URL 可安全用于 HTTP Header。
- Base64URL 不包含标准 Base64 的 `+`、`/` 和 `=`。
- API Key 可以带稳定前缀，例如 `sk-`。
- 前缀只用于识别凭证类型，不提供安全性。
- 完整 Key 在创建成功响应中只返回一次。
- 后续列表和详情接口不得再次返回完整 Key。
- 用户丢失完整 Key 后只能重新创建或轮换。
- 后端不得通过“找回”接口恢复完整 Key。
- 日志不得记录完整 API Key。
- Redis Key 不得包含完整 API Key。
- 异常消息不得包含完整 API Key。
- 监控标签不得包含完整 API Key。
- OpenAPI 示例不得放入真实或完整 API Key。

## 5. API Key 数据库存储决策

- 当前批准方案不是对称加密保存完整 API Key。
- 当前批准方案只保存不可逆查找摘要。
- 摘要算法为用途隔离的 HMAC-SHA256。
- HMAC Secret 必须来自环境变量或 Secret 管理系统。
- HMAC Secret 禁止存入数据库。
- HMAC Secret 禁止写入 YAML 默认值。
- HMAC Secret 禁止写入日志。
- 64 字节原始 Key 经 HMAC-SHA256 后仍是固定 32 字节摘要。
- 因此 `key_digest` 的数据库长度约束为 32 字节是正确的。
- `key_digest` 与原始 Key 的 64 字节安全随机长度不冲突。
- 认证时对客户端提交的完整 Key 重新计算 HMAC。
- 然后使用 `key_digest` 进行 B-tree 唯一等值查询。
- 不需要先解密数据库中全部 Key。
- 不需要批量把明文 Key 部署到 Redis。
- 不需要把可恢复密文写入 PostgreSQL。
- `key_hint` 保存完整 Key 的末尾四个 Base64URL 字符。
- `key_hint` 只用于展示类似 `sk-••••Ab9_` 的脱敏值。
- `key_hint` 不是认证条件。
- `key_hint` 不建立唯一索引。
- `key_hint` 不用于数据库查找。
- `display_name` 已被用户否决，不加入当前表。
- `credential` 可恢复密文字段已被用户否决。
- `secret_digest` 和 `key_digest` 不应重复设计成两个同义字段。

## 6. `user_api_key` 已批准结构

- SQL 文件：`sql/014_create_user_api_key.sql`。
- `id BIGINT GENERATED ALWAYS AS IDENTITY`。
- 用户要求 API Key 主键使用数据库自增 ID。
- 对外 PathVariable 不直接暴露 BIGINT。
- 对外资源 ID 必须使用项目统一 Base64URL Long 编码。
- `login_identity_id BIGINT NOT NULL`。
- 该字段逻辑关联 `userloginidentity.id`。
- 项目禁止物理外键。
- `key_digest BYTEA NOT NULL`。
- `key_digest` 必须恰好 32 字节。
- `key_hint VARCHAR(4) NOT NULL`。
- `key_hint` 必须是四个 Base64URL 字符。
- `status SMALLINT NOT NULL DEFAULT 1`。
- 状态 `0=DISABLED`。
- 状态 `1=ENABLED`。
- 状态 `2=DELETED`。
- `DELETED` 是不可恢复的软删除状态。
- 用户列表只显示 `DISABLED` 和 `ENABLED`。
- `expires_at TIMESTAMPTZ`。
- `expires_at IS NULL` 表示永不过期。
- 不使用 `-1 TTL` 写入时间戳字段。
- `row_version BIGINT NOT NULL DEFAULT 0`。
- `row_version` 用于乐观锁，当前用户允许保留。
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`。
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`。
- `deleted_at TIMESTAMPTZ`。
- 状态为 `0` 或 `1` 时 `deleted_at` 必须为空。
- 状态为 `2` 时 `deleted_at` 必须非空。
- 现有 SQL 使用触发器自动刷新 `updated_at`。

## 7. `user_api_key_model` 最终决策

- SQL 文件：`sql/015_create_user_api_key_model.sql`。
- 该表只保存 API Key 与 AI 模型的授权关系。
- 一行代表一个 API Key 被允许调用一个模型。
- 不把 `ai_model` 复制成 API Key 专用模型表。
- 所有现有 AI Model 都可以被 API Key 授权调用。
- `user_api_key_id BIGINT NOT NULL`。
- `ai_model_id BIGINT NOT NULL`。
- 联合主键为 `(user_api_key_id, ai_model_id)`。
- 联合主键支持根据 API Key 查询允许模型集合。
- 联合主键支持快速检查某个 Key 是否拥有某模型。
- `ai_model_id` 上另建反向普通索引。
- 当前索引名为 `idx_user_api_key_model_ai_model`。
- 反向索引用于模型侧查询和孤儿检查。
- 不建立物理外键。
- 写入映射前必须批量确认 API Key 和模型存在。
- 禁止逐模型执行 Mapper 查询造成 N+1。
- 模型授权集合使用多行关系表达，满足第一范式。
- 映射表不需要独立自增 ID。
- 映射表不需要状态字段。
- 映射表不需要软删除字段。
- 最新用户纠正：该映射表允许硬删除。
- 取消单个模型授权时直接物理删除对应映射行。
- API Key 被软删除时可以在同一事务硬删除其全部映射。
- 这是删除“授权关系”，不是删除 API Key 历史主记录。
- `user_api_key` 本身仍然禁止硬删除。

## 8. 模型禁用、删除和映射残留

- 映射存在不等于模型当前可用。
- API 请求首先需要确认请求模型当前可用。
- 模型缓存是运行时第一层快速判断。
- PostgreSQL 是最终真实数据源和兜底。
- 模型已禁用或已删除时不得继续调用上游。
- 即使映射行暂时存在，也不能绕过模型状态检查。
- 然后再检查 API Key 与模型的授权映射。
- 运行时授权条件是“模型可用”与“映射存在”同时成立。
- 不能只凭 `user_api_key_model` 一行决定模型可调用。
- 用户查看某个 API Key 的模型列表时先批量读取映射。
- 再批量用模型缓存或批量数据库查询补充模型信息。
- 禁止为每一行映射逐个查询模型。
- 缓存中不存在的模型可以显示为已停用或不可用。
- 也可以在产品层选择不展示不可用模型。
- 具体展示策略尚未最终编码。
- 取消模型授权时可以直接硬删除映射行。
- 管理员禁用模型时不要求同步物理删除全部映射。
- 历史 Usage 中的 `ai_model_id` 必须继续保留。

## 9. `ai_model_api_usage` 已批准结构

- SQL 文件：`sql/016_create_ai_model_api_usage.sql`。
- 每次外部 HTTP 模型调用产生一条核心 Usage。
- `id BIGINT GENERATED ALWAYS AS IDENTITY`。
- `key_digest BYTEA NOT NULL`。
- `ai_model_id BIGINT NOT NULL`。
- `billing_status SMALLINT NOT NULL DEFAULT 0`。
- `prompt_tokens BIGINT`。
- `completion_tokens BIGINT`。
- `cached_prompt_tokens BIGINT`。
- `charged_quota_minor BIGINT`。
- `finish_reason VARCHAR(64)`。
- `failure_code VARCHAR(64)`。
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`。
- `settled_at TIMESTAMPTZ`。
- `key_digest` 必须为 32 字节。
- Token 字段为空表示尚未取得最终 Usage。
- Token 字段非空时必须大于等于零。
- 缓存输入 Token 不得大于输入 Token。
- 最终扣费不得为负数。
- `settled_at` 不得早于 `created_at`。
- `finish_reason` 只保存受控短枚举值。
- `failure_code` 只保存安全的受控错误码。
- 禁止保存完整异常堆栈到该表。
- 禁止保存上游敏感响应到该表。
- 禁止保存提问正文。
- 禁止保存回答正文。
- 禁止保存会话 ID。
- 禁止保存消息 ID。
- 禁止保存登录身份 ID。

## 10. `ai_model_api_usage_detail` 已批准结构

- SQL 文件：`sql/017_create_ai_model_api_usage_detail.sql`。
- 该表与核心 Usage 是一对一逻辑关系。
- `id BIGINT GENERATED ALWAYS AS IDENTITY`。
- `usage_id BIGINT NOT NULL`。
- `vendor_snapshot VARCHAR(128) NOT NULL`。
- `is_stream BOOLEAN NOT NULL`。
- `reserved_quota_minor BIGINT NOT NULL`。
- `settlement_delta_minor BIGINT`。
- `usage_id` 上有唯一约束。
- 唯一约束同时提供精确 B-tree 查询能力。
- `usage_id` 逻辑关联 `ai_model_api_usage.id`。
- 不建立物理外键。
- `reserved_quota_minor` 保存预扣成功的最小额度单位。
- `settlement_delta_minor` 等于最终实际扣费减预扣额度。
- 正数差额表示需要补扣。
- 负数差额表示需要退款。
- 零表示无需调整。
- NULL 表示尚未完成结算。
- `vendor_snapshot` 用于记录本次实际路由供应商。
- `is_stream` 区分 SSE 与非流式请求。
- 该表不保存请求正文。
- 该表不保存响应正文。
- 该表不保存客户端幂等键。
- 该表不保存价格倍率快照。
- 该表不保存会话或消息字段。

## 11. 幂等性最终决定

- 用户明确决定 API Usage 不设计广义客户端幂等键。
- 外部 Agent 通常不会统一发送 `X-Client-Request-Id`。
- OpenAI 兼容调用也不能假设所有客户端提供幂等标识。
- 因此当前表没有 `idempotency_key_digest`。
- 每个到达服务端的 HTTP 请求被视为一次独立调用。
- 客户端断线后重试可能形成新的 Usage。
- 新请求可能产生新的预扣费。
- 服务端不能把两个独立请求自动判定为同一请求。
- 已知的单个请求在上游失败时仍可按自身状态退款。
- 两次独立请求都成功时不会因为内容相同自动退款其中一次。
- 该风险是用户当前明确接受的简化。
- 后续不得未经批准重新加入幂等字段。

## 12. 预扣公式

```text
reservationOutputTokens = ceil(effectiveMaxOutputTokens / 3)

reservedMinor = ceil(
    (
        estimatedPromptTokens * inputRatio
        + reservationOutputTokens * outputRatio
    )
    * 100 / 80000
)
```

- 三分之一只降低预扣门槛。
- 三分之一不改变上游真实最大输出能力。
- 三分之一不作用于最终实际结算。
- 当前 H5/Android 默认使用模型的完整 `maxOutputTokens` 计算预扣上限。
- API 请求如果允许更小的客户端输出上限，应计算有效上限。
- 有效上限建议为客户端上限与模型上限的较小值。
- 同一个有效上限必须同时用于预扣和传给上游。
- 禁止预扣按较小上限而上游仍按较大上限输出。
- 如果客户端没有提供上限，则使用模型配置上限。

## 13. 最终结算公式

```text
uncachedPromptTokens = promptTokens - cachedPromptTokens

actualMinor = ceil(
    (
        uncachedPromptTokens * inputRatio
        + cachedPromptTokens * cachedInputRatio
        + completionTokens * outputRatio
    )
    * 100 / 80000
)

settlementDeltaMinor = actualMinor - reservedMinor
```

- 最终输出使用上游报告的完整 `completionTokens`。
- 最终输出不得再除以三。
- 差额小于零时退回余额。
- 差额大于零时补扣余额。
- 差额等于零时不调整余额。
- 补扣会导致余额为负数时不得直接写负余额。
- 此时应保留已预扣额度并转入 `RECONCILE_REQUIRED`。
- 上游失败且没有可靠最终 Usage 时按受控失败策略退款或待对账。
- API Key 方案应直接复用该计算器代码。
- 不得仅复制公式到另一个类后各自演进。

## 14. API Key 与统一余额

- API Key 调用不要求登录 Cookie 或 Access Token。
- 认证成功后仍会得到 API Key 所属 `login_identity_id`。
- 该 ID 用于查找同一条 `user_membership_quota`。
- 预扣必须对该额度行执行 `FOR UPDATE` 锁定。
- 周期过期激活逻辑必须与 H5/Android 相同。
- 余额不足时整个预扣事务回滚。
- 余额扣减、核心 Usage 插入和 Detail 插入必须位于同一事务。
- 任一 SQL 影响行数异常时整个事务回滚。
- 预扣事务提交成功前不得调用 `8317`。
- 最终结算使用新的短事务再次锁定同一额度行。
- 退款或补扣与 Usage 状态更新必须原子提交。
- API Key Usage 表没有用户 ID 不影响扣款。
- 所属用户 ID 来自认证阶段的 `user_api_key` 主记录。
- Usage 用 `key_digest` 保留凭证维度审计关系。

## 15. H5、Android 与 API Key 缓存一致性

- H5/Android 已经有按用户 ID 缓存的用户资料 JSON。
- 该缓存包含账号状态、会员或额度展示相关信息。
- 缓存采用懒加载。
- 缓存过期后下一次读取会回源 PostgreSQL 并重新设置 TTL。
- 未过期时不应每次访问都盲目续期。
- API Key 认证得到用户 ID 后可以复用同一份用户缓存读取路径。
- 但真实余额扣减不能只改 Redis。
- 真实余额扣减必须以 PostgreSQL 锁行事务为准。
- 预扣事务提交后必须删除同一用户的相关缓存。
- 最终结算事务提交后也必须删除同一用户的相关缓存。
- 后续 H5 或 Android 读取时会懒加载最新余额。
- API Key 请求也会看到同一份最新账号状态。
- 这才是三条链路的余额一致性边界。
- 该方案是 Cache-Aside，不是分布式强一致事务。
- 数据库提交后、缓存删除前宕机仍存在短暂旧值窗口。
- 项目接受该窗口并依赖有限重试和 TTL 收敛。

## 16. 建议的外部请求链路

```text
外部 Agent
→ Cloudflare/API 子域名
→ Java API 入口
→ 解析 Authorization: Bearer <API Key>
→ 计算用途隔离 HMAC-SHA256
→ Counting Bloom 快速否定（未来能力）
→ PostgreSQL 查 user_api_key
→ 校验状态、过期和所属账号
→ 校验模型当前可用
→ 校验 user_api_key_model 授权
→ 预估输入 Token
→ 复用三分之一输出预扣计算器
→ 同事务锁余额并写 API Usage/Detail
→ 提交后删除用户缓存
→ 调用 127.0.0.1:8317
→ 向客户端转发 SSE
→ 捕获最终 Usage 或失败结果
→ 同事务多退少补并更新 Usage/Detail
→ 提交后再次删除用户缓存
```

- 对外主路径方向是 OpenAI 兼容的 `/v1/chat/completions`。
- 是否额外增加 `/sdk` 前缀尚未最终编码。
- 不建议让客户端知道内部 `8317` 地址。
- Cloudflare Worker 只负责边缘接入，不负责最终扣费事务。
- 计费和授权必须在 Java 服务端完成。
- 8317 网络调用不得放进数据库事务。

## 17. API Key 管理接口语义

- 创建 API Key 使用 POST。
- 创建请求包含过期时间和允许模型集合。
- 创建成功只返回一次完整 Key。
- 列表接口查询当前用户的启用和禁用 Key。
- 列表不返回软删除 Key。
- 列表按 `created_at DESC` 稳定分页。
- 详情接口返回脱敏 Key、状态、过期时间和模型信息。
- 更新接口可以修改启用/禁用状态。
- 更新接口可以修改过期时间。
- 模型授权可以采用整组替换或差量修改，尚未最终确定 HTTP 形式。
- 无论 HTTP 形式如何，映射写入必须批量完成。
- 删除 API Key 的 HTTP DELETE 表示业务软删除。
- 该操作更新 `status=DELETED` 和 `deleted_at`。
- 该操作不得对 `user_api_key` 执行 SQL DELETE。
- 同一事务可以硬删除 `user_api_key_model` 映射。
- 单独取消模型权限允许对映射执行 SQL DELETE。
- 更新和删除应检查 `row_version` 防止覆盖并发修改。
- 对外 API Key 资源 ID 遵守固定 11 字符 Base64URL PathVariable 规范。

## 18. 计数布隆过滤器：用户批准的目标

- 用户要求全项目只保留一套 Redis 计数布隆引擎。
- 唯一公共入口应为 common 模块的 `CountingBloomFilter`。
- 身份模块不应再维护第二套 Lua、Bucket 和 Redis Store。
- 身份实现只负责规范化、HMAC、数据库分页和失败降级。
- 通用引擎负责分片、Lua、幂等、初始化和 Redis 状态。
- 用户明确要求固定使用 `v1` Key。
- 用户明确禁止自动切换到 `v2`。
- 用户明确禁止生成 `v1-g<时间戳>`。
- 用户明确不要 Generation 切换。
- 用户要求每次应用启动都清空旧过滤器。
- 清空后必须从 PostgreSQL 全量重建。
- 重建不得信任旧 Redis 计数内容。
- 重建在后台线程执行。
- `ApplicationReadyEvent` 不得被全量加载阻塞。
- 多实例通过固定租约 Key 互斥。
- 只有租约持有者可以清空和重建。
- 初始化状态为 `BUILDING → READY → ACTIVE`。
- `BUILDING`、`READY`、`DEGRADED` 查询返回 `UNAVAILABLE`。
- 身份业务收到 `UNAVAILABLE` 后回源 PostgreSQL。
- 该行为是 Fail Open，避免空过滤器假阴性。

## 19. 计数布隆初始化目标

- 启动后后台任务先领取初始化租约。
- 调用 `reinitialize()` 强制重建。
- 即使配置没有变化也必须重建。
- 首先把固定控制状态改为 `BUILDING`。
- 然后精确清理旧 Bucket、Receipt 和元数据。
- 大 Key 清理使用批量 `UNLINK`。
- 禁止使用 `FLUSHDB`。
- 禁止使用 `KEYS *`。
- 禁止逐 Key 网络往返。
- 首次上线需要读取旧控制 Hash 记载的完整旧 Key。
- 精确清理旧 `v1-g...` Bucket 和 Receipt。
- 清理旧 Generation 只做一次兼容迁移。
- 不得重新写入任何 Generation Key。
- 不得通过 Redis SCAN 猜测旧 Generation。
- 然后创建固定 v1 空 Bucket。
- PostgreSQL 按 ID 游标分页读取用户身份。
- 每页本地规范化并计算用途隔离 HMAC。
- 每页一次数据库查询。
- 每页一次 Redis Lua 批量写入。
- 构建成功后标记 `READY` 再激活为 `ACTIVE`。
- 最后释放初始化租约。

## 20. 计数布隆当前源码事实

- 当前 common 中仍有旧 `CountingBloomFilter`。
- 当前身份模块仍注入 `IdentityPresenceBloomStore`。
- 当前仍有 `RedisIdentityPresenceBloomStore`。
- 当前仍有 `ProtectedIdentityPresenceRecord`。
- 当前仍有 `RedisIdentityPresenceBloomStoreIntegrationTest`。
- 当前 Redis Store 内仍存在独立 Lua。
- 当前控制状态仍包含 `activeGeneration`。
- 当前控制状态仍包含 `buildingGeneration`。
- 当前身份实现仍生成 `v1-g...`。
- 当前 `RedisKeyFactoryTest` 仍断言 Generation Key。
- 因此批准的统一方案没有落入当前工作区。
- 也没有找到要求的固定版本 ADR。
- 目标 ADR 名称曾约定为 `docs/architecture/adr-2026-08-13-counting-bloom-fixed-version-rebuild.md`。
- 项目 AGENTS 规则默认要求双版本切换。
- 用户批准的固定 v1 方案与该默认规则存在冲突。
- 实施固定 v1 前必须补齐 ADR 记录例外。
- 不得假装用户口头批准自动修改了项目规则文件。

## 21. 项目工程强制规则摘要

- 模块依赖方向必须保持 `web → service → mapper → model → common`。
- Service 必须使用接口加 `Impl`。
- Controller 和其他 Service 只能依赖 Service 接口。
- 必须使用构造器注入和 final 字段。
- 多实现策略必须由 Registry 统一选择。
- 禁止业务 switch 分发策略。
- Java 非直观安全、事务和并发逻辑必须写中文注释。
- 每个顶级 Java 类型必须有说明职责的中文 JavaDoc。
- 项目禁止物理外键。
- 逻辑关联字段必须有索引和孤儿检查。
- 禁止 N+1 数据库和 Redis I/O。
- 批量默认 500，单批不得超过 2000。
- Redis Key 必须由 `RedisKeyFactory` 生成。
- 缓存一致性采用提交后删除的 Cache-Aside。
- 对外 BIGINT PathVariable 必须使用 11 字符 Base64URL。
- 每个公开 Controller 必须有中文 `@Tag`。
- 每个公开方法应有中文 `@Operation`。
- 第一阶段默认只写代码和测试源码，不运行验证。
- 第二阶段必须说明命令、范围、基础设施和写入影响后重新取得授权。

## 22. 当前未决但不能擅自决定的问题

- 外部路径是否最终为纯 `/v1/chat/completions`。
- 是否额外保留 `/sdk` 路径层级。
- API 管理端模型授权采用整组 PUT 还是差量接口。
- API Key 是否增加独立最小 Principal Redis 缓存。
- API Key Bloom 的 Definition、容量和误判率。
- API Key 软删除时是否同步减少 Bloom 计数。
- 非流式 API 是否第一期同时开放。
- 客户端 `max_tokens` 与模型最大值的最终兼容字段策略。
- API Usage 崩溃恢复如何处理没有倍率快照的情况。
- API Key 创建数量上限和每用户限额。
- API Key 请求级限流维度和默认阈值。
- API Key 的外部错误码和 OpenAI 兼容错误体。
- 这些问题需要在真正实现相应代码前明确。
- 不要为了“完整架构”一次性增加未批准字段或组件。

## 23. 建议的后续实施顺序

- 第一步，先让用户确认本文中的当前事实和最终边界。
- 第二步，修正文档中与最新决定冲突的地方。
- 最新决定包括映射表允许硬删除。
- 最新决定包括 API 预扣复用三分之一输出规则。
- 第三步，为 `014` 和 `015` 增加最小持久化契约测试。
- 第四步，复核 `016`、`017` 是否满足最新预扣证据需求。
- 第五步，在 model 模块增加最小实体和枚举。
- 第六步，在 mapper 模块增加批量查询与写入 Mapper。
- 第七步，在 service 模块实现 API Key 生成、认证和 CRUD 接口/Impl。
- 第八步，实现模型映射批量替换或差量服务。
- 第九步，实现 API Usage 预扣与结算接口/Impl。
- 第十步，直接复用现有 Token 额度计算器。
- 第十一步，接入同一用户余额锁行和提交后缓存删除。
- 第十二步，实现对外 OpenAI 兼容 Controller。
- 第十三步，复用现有 8317 流式策略 Registry。
- 第十四步，增加 SSE 中断、上游失败和结算测试源码。
- 第十五步，经明确授权后执行隔离的第二阶段测试。
- 计数布隆统一应作为独立任务处理。
- API Key Bloom 再作为后续独立任务处理。
- 不建议把数据库、公开 API、布隆统一一次性混成一个巨大改动。

## 24. 可直接用于新任务的最短启动提示

```text
请先阅读：
docs/handoffs/2026-08-13-api-key-sdk-billing-and-counting-bloom-context.md

当前位于 main，工作区很脏，禁止覆盖语音、前端和 application.yml 修改。
API Key 目前只有 014-017 SQL、孤儿检查、API Usage 契约测试和文档草案；
生产 Java API 链路尚未实现。

关键决定：
1. 原始 Key 为 64 随机字节 + Base64URL，完整值只显示一次。
2. 数据库只存 HMAC-SHA256 key_digest 和末四位 key_hint。
3. user_api_key 软删除；user_api_key_model 授权关系允许硬删除。
4. API Usage 与 Detail 分表，不保存内容、会话、幂等键和倍率快照。
5. API 预扣必须复用 H5/Android：最大输出只取向上取整三分之一，输入照常参与。
6. 最终结算使用完整真实 Usage，并对同一 user_membership_quota 多退少补。
7. 当前身份 Bloom 仍是旧 Generation 实现；固定 v1 统一计划尚未落地。

开始任何修改前先核对实际文件，并遵守 AGENTS.md 的两阶段测试授权规则。
```
