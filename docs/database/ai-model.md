# AI Model 数据库关系与恢复说明

`ai_model`、`ai_model_icon` 和 `ai_model_capability` 的主键都由应用层共享 `SnowflakeIdWorker` 生成。`ai_model_icon` 保存可被多个模型复用的图标名称、最终 HTTPS 地址、可选描述和可空 `object_key`：本地图片保存 OSS Object Key，已验证的外部地址保存 `NULL`。`ai_model.icon_id` 与 `ai_model_icon.id` 建立多对一逻辑关联。`ai_model_capability` 保存模型能力明细，并通过 `ai_model_id` 与 `ai_model.id` 建立一对多逻辑关联；能力主键只作为内部标识，不通过管理端 API 暴露。

本次主键调整以 `ai_model_icon` 和 `ai_model_capability` 均为空表为前提，直接修改 004、006 建表脚本，不提供存量表结构迁移。已经创建过这两张表的环境必须在部署新版应用前重建空表；只替换应用代码不会改变现有数据库列的 Identity 属性。

项目明确不为上述逻辑关系创建物理外键。`ai_model.icon_id` 和 `ai_model_capability.ai_model_id` 均建有普通索引。创建或修改模型前，Service 必须确认非空 `icon_id` 对应图标资源存在；新增模型时必须先生成并写入模型，再在同一个 PostgreSQL 本地事务中批量写入能力，任一步骤失败时整笔事务回滚。不得在未验证目标存在的入口写入逻辑关联记录。

模型主记录不提供物理删除。模型退出使用范围只能将 `ai_model.is_enabled` 更新为 `FALSE`。图标资源被模型引用时不得物理删除；确需删除时，必须先在同一事务内清空或替换所有模型的 `icon_id`，再删除图标资源。模型选择图标时对图标行取得共享锁，图标删除时取得排他锁并统计全部模型引用，从而与并发写入串行化。字段编辑使用 `row_version` 乐观锁，PATCH 和实际启停修改都会原子递增版本；旧版本写入必须返回冲突，禁止覆盖并发修改。

能力编辑采用整组替换：在同一个 PostgreSQL 本地事务中先完成带版本条件的模型主表更新，再删除该模型旧能力并一次批量插入新能力。这里的能力明细删除不是模型物理删除；任一步失败时主表版本、字段和能力集合全部回滚。未来若经过 ADR 增加模型删除能力，必须先删除 `ai_model_capability`，再删除 `ai_model`，并在同一事务中完成。

能力孤儿数据通过 `sql/checks/ai_model_capability_orphans.sql` 检查，图标逻辑关联孤儿数据通过 `sql/checks/ai_model_icon_orphans.sql` 检查，正常结果都必须为空。若发现孤儿记录，应先阻断对应写入入口并核对审计记录：能够确认目标记录时在事务内恢复；无法确认真实归属时，先备份孤儿行，再经人工审批清理，禁止自动猜测关联或直接级联删除。

所有模型读取都通过 `ai_model LEFT JOIN ai_model_icon` 得到最终 `icon_url`。HTTP 响应额外返回 `iconPublicId` 供管理端选择，但 Redis 中的启用模型 AES-256-GCM 快照继续只保存原有 `icon` URL，不修改 Key、Schema Version 或值结构。图标 URL 被启用模型引用时，数据库提交成功后刷新快照；只修改图标名称或描述不刷新。

PostgreSQL 与 OSS 不使用分布式事务。上传成功但数据库创建失败时应用尽力删除新对象；删除图标、切换外链或更换文件后尽力删除旧对象。删除失败只记录低基数指标和脱敏日志，不建立重试表，项目接受极少量 OSS 残留。HTTP 契约见 `docs/admin-ai-model-icon-api.md`，部署和恢复操作见 `docs/operations/ai-model-icon-oss.md`。

图标格式扩展为 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和安全 SVG 只改变文件验证与 OSS 后缀规则，不改变 `ai_model_icon` 字段、唯一约束、逻辑关联或任何数据库索引。外部 URL 的 `object_key` 仍为 `NULL`，本地上传仍保存精确 OSS Object Key。
