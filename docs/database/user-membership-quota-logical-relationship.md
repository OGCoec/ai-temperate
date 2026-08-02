# user_membership_quota 与 userloginidentity 逻辑关系

## 关系定义

user_membership_quota.login_identity_id 逻辑关联 userloginidentity.id，业务上是一对一关系。
唯一约束同时提供 B-tree 查询索引，并阻止同一登录身份重复创建当前会员额度记录。
数据库不创建物理外键，关系完整性由注册事务、影响行数校验、孤儿巡检和恢复流程共同补偿。

## 写入验证

注册 Service 必须先在同一个 PostgreSQL 本地事务中成功写入 userloginidentity 和 user_profile，
再写入 user_membership_quota。三次插入的影响行数都必须等于 1，任一步失败时回滚整个事务。
新注册用户由数据库默认值获得 FREE 会员等级和 5000 最小单位额度，对外解释为 50.00 额度。
注册业务使用统一 UTC 时钟把 quota_period_ends_at 初始化为当前时间，并保持 quota_period_started_at 为空；
该状态表示额度尚未开始消耗，后续首次模型调用通过已经到期的结束时间开启正式周期。

## 删除顺序

删除用户时必须在同一个 PostgreSQL 本地事务中先删除 user_membership_quota，再删除 user_profile，
最后删除 userloginidentity。每一步都必须校验影响行数，禁止假设数据库会自动级联处理。

## 孤儿数据检查

巡检 SQL 位于 sql/checks/user_membership_quota_orphans.sql。生产环境应该由受控离线任务定期执行并记录结果数量，
不得通过无边界业务接口直接返回全部巡检结果。

## 恢复方式

发现孤儿记录后必须先保存待处置记录快照并停止对应写入口。能够从权威审计或备份恢复身份时，
先恢复 userloginidentity，再复查关联；无法证明主记录合法存在时，在人工确认后删除或归档孤儿记录。
修复必须在 PostgreSQL 本地事务中执行并校验影响行数，随后重新运行孤儿检查直至结果为空。

## 接受风险

应用层存在性校验不能提供物理外键的绝对关系完整性。人工 SQL、缺陷脚本或异常恢复仍可能产生孤儿记录，
项目明确接受该窗口，并通过统一 Service 写入口、本地事务、唯一索引、巡检和可审计恢复降低风险。
