# API Key 模型用量逻辑关系

## 关系定义

`ai_model_api_usage.key_digest` 逻辑关联 `user_api_key.key_digest`，`ai_model_api_usage.ai_model_id` 逻辑关联 `ai_model.id`。`ai_model_api_usage_detail.usage_id` 逻辑关联 `ai_model_api_usage.id`，业务上是一对一关系，唯一约束同时提供 B-tree 精确查询索引。

这些关系不建立物理外键。应用通过写入前验证、同一个 PostgreSQL 本地事务、影响行数检查、唯一约束、普通索引和离线孤儿检查补偿关系完整性风险，但不宣称具有物理外键提供的绝对完整性。

## 写入和预扣顺序

API Key 认证成功后，Service 必须先验证凭证处于启用且未过期状态，并验证请求模型仍然存在、启用且包含在该 API Key 的模型授权集合中。随后读取本次请求使用的模型价格并计算预扣额度。

余额扣减、`ai_model_api_usage` 插入和 `ai_model_api_usage_detail` 插入必须位于同一个 PostgreSQL 本地事务中。核心用量初始状态为 `RESERVED`，详情记录保存已经扣减的 `reserved_quota_minor`。余额不足、关联记录失效或任一 SQL 影响行数不等于预期值时必须回滚整个事务，并且禁止调用上游模型。

上游返回最终用量后，在一个新的短事务中写入实际 Token、最终扣费 `charged_quota_minor` 和 `settlement_delta_minor`。差额为正时补扣，为负时退款，为零时不修改余额；无法确定最终结果时转为 `RECONCILE_REQUIRED`。

## 删除顺序

`user_api_key` 使用软删除，历史 API 用量不得随凭证删除。AI 模型禁用或业务删除时同样保留历史用量，展示层可以把无法加载当前模型信息的历史记录标记为模型已停用。

如果依法执行历史用量物理清理，必须按受控时间范围分批处理，并在同一 PostgreSQL 本地事务中先删除 `ai_model_api_usage_detail`，再删除 `ai_model_api_usage`。每批必须校验影响行数，不得通过无边界在线接口清空历史记录。

## 孤儿数据检查

核心用量与 API Key、AI 模型之间的巡检 SQL 位于 `sql/checks/ai_model_api_usage_orphans.sql`。核心用量与一对一预扣详情之间的双向巡检 SQL 位于 `sql/checks/ai_model_api_usage_detail_orphans.sql`。正常结果均为空集。

生产环境只能由受控离线任务分批执行巡检并记录结果数量，禁止把无边界孤儿查询直接暴露为业务接口。

## 恢复方式

发现缺少 API Key 或模型主记录的历史用量时，必须先暂停相关清理任务并保存异常记录快照。能够从权威审计或备份恢复主记录时先恢复主记录，再重新运行巡检；无法证明主记录合法存在时只能由人工确认后归档异常用量。

发现核心用量缺少详情或详情缺少核心用量时，不得猜测预扣金额或自动补造计费证据。应将仍处于活动计费状态的记录转入人工对账，核对余额流水和上游账单后，在 PostgreSQL 本地事务中恢复或归档数据。

## 接受风险

应用层存在性校验不能提供物理外键的绝对关系完整性。人工 SQL、缺陷脚本或异常恢复仍可能制造孤儿记录。项目明确接受该风险，并通过统一 Service 写入口、本地事务、唯一索引、离线巡检和人工对账降低影响。
