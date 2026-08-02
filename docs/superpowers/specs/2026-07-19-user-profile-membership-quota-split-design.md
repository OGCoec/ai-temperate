# 用户状态与会员额度拆表设计

## 目标

将现有 `user_profile` 中的会员等级移入独立表，形成两个一对一逻辑关联的数据边界：

- `user_profile` 保存用户展示资料和账号状态。
- `user_membership_quota` 保存当前会员等级和以最小单位表示的可用额度。

两个表均通过 `login_identity_id` 逻辑关联 `userloginidentity.id`，不创建物理外键；每个关联字段使用唯一 B-tree 索引保证一名用户最多只有一条当前记录。

## 数据结构

### 002：user_profile

保留字段：

- `id`
- `login_identity_id`
- `display_name`
- `avatar_url`
- `gender`
- `birthday`
- `bio`
- `account_status`
- `status_reason`
- `frozen_until`
- `status_changed_at`
- `created_at`
- `updated_at`

删除字段：

- `membership_tier`

保留主键、`login_identity_id` 唯一索引、性别检查约束、账号状态检查约束以及状态和更新时间触发器。

### 005：user_membership_quota

字段：

- `id`
- `login_identity_id`
- `membership_tier`
- `quota_balance_minor BIGINT NOT NULL DEFAULT 5000`
- `quota_period_started_at TIMESTAMPTZ`
- `quota_period_ends_at TIMESTAMPTZ`

`quota_balance_minor` 使用非负检查约束。当前固定缩放比例为 100，例如数据库值 `1234` 表示 `12.34` 额度。新注册用户的数据库默认值为 `5000`，表示实际额度 `50.00`。
新用户注册时 `quota_period_started_at` 为空，`quota_period_ends_at` 由注册业务使用统一 UTC 时钟写入当前时间；周期结束时间是后续额度消费判断旧周期是否失效的依据。

## Mapper 与查询边界

`UserProfileMapper.xml` 的注册插入只写资料和账号状态，不再写 `membership_tier`。

新增 `UserMembershipQuotaMapper.xml`，负责：

- 注册时写入 `login_identity_id` 和额度周期边界，会员等级 `FREE` 和数据库值 `5000`（实际 `50.00`）的额度由数据库默认值统一生成。
- 按 `login_identity_id` 查询会员额度。

后续模型调用扣费应在独立额度 Service 中增加带余额下限条件的原子 SQL；本次拆表不实现扣费 Mapper 方法、Service 或 HTTP API。

`UserLoginIdentityMapper.xml` 的认证结果映射删除 `membership_tier`，认证查询继续连接 `user_profile` 获取当前账号状态和显示名称，不连接 `user_membership_quota`。

认证和额度必须分开：

- 密码登录、验证码登录、Access Token 校验、Refresh Token 刷新和忘记密码只读取当前账号状态。
- 会员展示和模型额度检查、扣减才读取 `user_membership_quota`。
- 登录接口若以后需要同时返回会员额度，应在登录成功后通过独立权益服务查询，不把额度并入认证上下文。

## 业务流程影响

### 注册

在同一个 PostgreSQL 本地事务中依次写入：

1. `userloginidentity`
2. `user_profile`
3. `user_membership_quota`

每次插入都必须验证影响行数为 1，任意一步失败时回滚整个事务。

### 登录

密码登录和验证码登录继续使用身份表与 `user_profile` 校验 `account_status=ACTIVE`。会员等级和额度不参与身份认证，因此不连接 005。

### 忘记密码

身份定位仍由 `userloginidentity` 完成，账号可用性由 `user_profile.account_status` 判断，密码更新仍写 `userloginidentity`。该流程不访问 005。

### Access Token 与 Refresh Token

Access Token 保持只包含 `sub`、`jti`、`ver`、`iat`、`exp`，不加入会员等级或额度。

Refresh Token 的 Redis 会话键保持：

```text
ait:<env>:auth:session:v3:rt:<HMAC(refreshToken)>
```

其 Hash 值保持以下字段：

```text
userId
publicId
csrfHash
email
phone
deviceHash
```

Refresh Token 刷新时先校验 Redis 会话，再使用 `userId` 查询数据库中的当前账号状态。该查询只需要 `userloginidentity` 与 `user_profile`，不需要连接 005。Refresh Session Hash 不保存 `membershipTier` 或 `quotaBalanceMinor`；个人中心独立资料缓存可以保存短期展示快照，但必须在额度或会员变更提交后失效，且不得参与认证或权威扣费。

## 一致性与安全边界

- 账号冻结或注销必须立即阻止登录、Access Token 认证和 Refresh Token 刷新。
- 会员等级和额度不决定用户是否能够完成身份认证。
- 模型调用前必须单独校验并原子扣减额度；扣减 SQL 必须包含余额充足条件并检查影响行数。
- 不在日志、Token 或 Redis 会话中记录完整额度变更流水；额度审计如有需求应另建流水表。
- 删除用户时，在同一个本地事务中先删除 `user_membership_quota` 和 `user_profile`，再删除 `userloginidentity`。
- 为 `user_membership_quota.login_identity_id` 增加孤儿数据检查 SQL，并更新现有逻辑关系文档。

## 测试范围

第一阶段只编写代码和必要测试，不执行测试或编译。进入用户明确批准的第二阶段后，再验证：

- 注册同时写入三张表且任一步失败全部回滚。
- 登录、验证码登录、忘记密码、Access Token 和 Refresh Token 仅依赖账号状态，不依赖 005。
- 会员默认值为 `FREE`，额度数据库默认值为 `5000`，实际额度为 `50.00`。
- `quota_balance_minor` 拒绝负数；原子扣减能力留待后续额度消费功能实现时验证。
- 两个逻辑关联字段的唯一性和孤儿检查 SQL 正确。
