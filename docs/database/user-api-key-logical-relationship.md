# 用户 API Key 逻辑关系

## 关系定义

`user_api_key.login_identity_id` 逻辑关联 `userloginidentity.id`；`user_api_key_model.user_api_key_id` 逻辑关联 `user_api_key.id`；`user_api_key_model.ai_model_id` 逻辑关联 `ai_model.id`。项目不建立物理外键，关系完整性由写入前批量验证、同一个 PostgreSQL 本地事务、唯一约束、普通索引、影响行数检查和离线孤儿巡检共同补偿，但不宣称具备物理外键的绝对保证。

## 写入和授权替换

创建 API Key 前必须确认登录身份存在，并一次批量验证 1～500 个模型均存在且启用；主 Key 与模型映射在同一事务写入，任一影响行数异常时整笔回滚。完整 Key 只进入创建响应，数据库仅保存完整 Key 的 HMAC-SHA256 摘要和末四位提示。

模型授权全量替换必须先按所有权和 `row_version` 锁定主 Key，再一次批量验证目标模型。缺失授权批量更新为 `REVOKED`，目标授权通过联合主键 UPSERT 新增或恢复为 `ACTIVE`；只有真实变化才增加主 Key 行版本。禁止在集合循环中逐条查询或写入数据库。

## 删除与恢复边界

HTTP `DELETE` 只把 `user_api_key.status` 更新为 `DELETED`，同时把全部有效 `user_api_key_model` 映射更新为 `REVOKED`。`user_api_key`、`user_api_key_model`、API Usage 及其详情均禁止执行 `DELETE FROM`；历史摘要唯一性继续保留，软删除 Key 不可恢复，也不能用相同摘要重新创建凭证。

模型授权的软撤销可以通过后续全量替换 UPSERT 恢复；主 Key 的 `DELETED` 状态不可通过普通更新恢复。禁用、删除或撤权提交后删除认证缓存；Bloom 减计数失败只产生假阳性并触发重建，认证真值仍由 PostgreSQL 决定。

## 孤儿数据检查

API Key 与登录身份的巡检 SQL 位于 `sql/checks/user_api_key_orphans.sql`；授权映射与 API Key、AI 模型的巡检 SQL 位于 `sql/checks/user_api_key_model_orphans.sql`。正常结果均为空集，生产环境只能由受控离线任务执行并记录结果数量。

发现孤儿时必须先暂停相关写入口并保存异常记录快照。能够由权威审计或备份证明主记录时，在 PostgreSQL 本地事务中恢复主记录或纠正关联；无法证明真实归属时进入人工处置，不得自动猜测关系、级联物理删除或重建完整 API Key。

## 接受风险

人工 SQL、缺陷脚本或异常恢复仍可能绕过应用校验产生孤儿记录。项目接受无物理外键的这一风险，通过统一 Service 写入口、批量 SQL、本地事务、缓存提交后失效、离线巡检和人工恢复降低影响。
