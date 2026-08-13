# API Key 模型用量与预扣费拆表设计

## 目标

为外部 API Key 模型调用新增独立的核心用量表和一对一计费详情表。结构复用网页版模型调用的预扣、结算和退款思路，但不保存会话、消息、提问正文、回答正文、客户端幂等键或价格倍率快照。

## 表职责

`ai_model_api_usage` 保存一次 HTTP 模型调用的 API Key 摘要、模型、计费状态、最终 Token 用量、最终实际扣费和终止结果。

`ai_model_api_usage_detail` 通过唯一的 `usage_id` 与核心用量保持一对一逻辑关系，保存上游供应商、是否流式、预扣额度和最终结算差额。

## 计费流程

1. API Key 认证和模型授权通过后，读取模型价格并计算预扣额度。
2. 在同一个 PostgreSQL 本地事务中扣减用户额度，同时创建核心用量和详情记录；余额不足或任一写入失败时整体回滚。
3. 上游返回最终 Usage 后，把实际 Token 和最终扣费写入核心用量，把“最终扣费减预扣额度”写入详情。
4. 差额大于零时补扣，小于零时退款，等于零时不调整余额。
5. 无法确定最终用量或结算结果时转为 `RECONCILE_REQUIRED`，由受控后台任务处理。

## 状态

沿用现有 `AiModelBillingStatus` 编码：

- `0=RESERVED`
- `1=SETTLED`
- `2=FAILED_REFUNDED`
- `3=RECONCILE_REQUIRED`
- `4=REFUNDED`

## 逻辑关联

项目不建立物理外键。`ai_model_api_usage.key_digest` 逻辑关联 `user_api_key.key_digest`，`ai_model_api_usage.ai_model_id` 逻辑关联 `ai_model.id`，`ai_model_api_usage_detail.usage_id` 逻辑关联 `ai_model_api_usage.id`。通过索引、同库事务、影响行数检查和孤儿检查 SQL 补偿关系完整性风险。

## 明确不包含

- 客户端幂等键或跨 HTTP 请求去重。
- 登录身份 ID、会话 ID或消息 ID。
- 提问和回答正文。
- 输入、缓存输入和输出价格倍率快照。
- Java Service、Mapper、Controller 或 HTTP 接口实现。

