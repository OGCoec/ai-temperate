# 用户认证上下文

登录与会话校验通过 `userloginidentity.id = user_profile.login_identity_id` 的逻辑关系一次查询认证上下文，不建立物理外键。

- `userloginidentity.password_version` 是密码凭据版本，默认从 `1` 开始。
- `userloginidentity.totp_enabled` 表示登录是否必须进入 TOTP 第二因子，默认 `false`。
- `userloginidentity.totp_secret_encrypted` 只保存与用户 ID 绑定的 AES-256-GCM 密文；关闭时必须在同一 SQL 中置 `NULL`，Base32 只用于十分钟设置响应。
- 用户主动创建或修改真实密码时必须递增 `password_version`。
- 注册和密码重置时必须基于用户本次提交的明文执行 `SHOPPING_V1` 强度校验；强度结果不进入登录身份表。
- 密码重置必须在同一 SQL 中同时更新密码哈希和凭据版本。
- `userloginidentity.created_at` 记录登录身份的创建时间，`updated_at` 记录该行最后一次变更时间。
- 仅因 `PasswordEncoder.upgradeEncoding` 升级哈希时使用旧哈希 CAS 更新，不修改 `password_version`；数据库更新时间触发器仍会刷新 `updated_at`。
- 邮箱和手机号所有权验证属于注册流程状态，不在登录身份表中保存独立的验证时间字段。
- 认证状态以 `user_profile.account_status` 为准：`0=ACTIVE`、`1=FROZEN`、`2=DISABLED`（数据库历史名称 `DEACTIVATED` 在认证域映射为 `DISABLED`）。
- 会员等级、额度和额度周期边界存放在 `user_membership_quota`，不进入认证上下文，也不参与登录、忘记密码、Access Token 或 Refresh Token 的账号可用性判断。
- 个人中心可以在认证完成后按内部用户 ID 读取独立短期资料缓存；该缓存不扩展 `SessionPrincipal`，
  不参与 Token 验证，并且不能作为额度预扣或最终结算的数据来源。
- 用户资料缺失或出现未知状态时认证必须安全拒绝。
- `totp_enabled` 与密文存在性不一致时认证必须安全拒绝并返回配置不可用，禁止静默绕过第二因子。

## 逻辑关系补偿

- 写入验证：创建资料状态和会员额度前验证 `userloginidentity` 已写入，且三次写入位于同一 PostgreSQL 本地事务。
- 删除顺序：先撤销用户会话并删除 `user_membership_quota` 和 `user_profile`，再删除 `userloginidentity`。
- 孤儿数据检查：使用 `sql/checks/user_profile_orphans.sql` 和 `sql/checks/user_membership_quota_orphans.sql` 分别定期扫描资料状态与会员额度记录。
- 密码版本检查：使用 `sql/checks/userloginidentity_invalid_password_version.sql` 扫描非法版本。
- 恢复方式：从审计备份恢复缺失资料；恢复完成前认证查询将其视为不可用账号。
- 接受风险：应用层校验无法提供物理外键的绝对关系完整性，项目明确接受该窗口。
