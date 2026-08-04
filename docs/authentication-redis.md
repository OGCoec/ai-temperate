# 认证模块 Redis 设计

## 通用规则

Key 统一由 `RedisKeyFactory` 生成：

```text
<项目>:<环境>:<业务域>:<对象>:<版本>:<类型>:<标识>
```

- 项目前缀固定为 `ait`。
- 敏感标识先规范化，再由服务端密钥执行 HMAC-SHA256，使用 43 字符 Base64URL 摘要。
- Key 中禁止出现原始安装 UUID、邮箱、手机号、IP、Token、验证码和 JSON。
- 注册、登录流程与找回密码继续使用认证 v2 Key；固定 RT 会话当前使用独立 v4 Key，v3 仅在迁移窗口内读取和清理。
- 项目尚未上线，不读取或迁移旧 v2 会话；旧短生命周期 Key 自然过期。
- 禁止使用 `KEYS`、全库 `SCAN` 或无界 Lua。

## 注册域

```text
ait:<env>:auth:register:v2:flow:<HMAC(registerToken)>
ait:<env>:auth:register:v2:challenge:<HMAC(challenge)>
ait:<env>:auth:register:v2:email-code:<HMAC>
ait:<env>:auth:register:v2:phone-code:<HMAC>
ait:<env>:auth:register:v2:conflict:<HMAC(device)>
ait:<env>:auth:register:v2:send-risk:<HMAC(device)>
ait:<env>:auth:register:v2:verify-risk:<HMAC(device)>
ait:<env>:auth:register:v2:block:<HMAC(device)>
```

- registerToken 空闲 TTL 十分钟、绝对生命周期三十分钟。
- 冲突、发送和组合验证风控使用固定五分钟窗口，后续失败不续窗口 TTL。
- 冲突与单渠道发送在第六次触发设备封禁两小时。
- 组合验证码总失败在第十一次触发设备封禁两小时。
- 验证码只保存 HMAC 摘要，TTL 五分钟；新码使同渠道旧码失效。

## 登录域

```text
ait:<env>:auth:limit:v2:password-failure:<HMAC(device)>
ait:<env>:auth:limit:v2:code-failure:<HMAC(device)>
ait:<env>:auth:limit:v2:login-block:<HMAC(device)>
ait:<env>:auth:login:v2:flow:<HMAC(flowToken)>
ait:<env>:auth:login:v2:challenge:<HMAC(challenge)>
ait:<env>:auth:login:v2:email-code:<HMAC(flowToken)>
ait:<env>:auth:login:v2:phone-code:<HMAC(flowToken)>
```

- 密码和验证码失败桶独立，固定窗口五分钟。
- 各桶前五次累计，第六次写入共享登录域 block，TTL 两小时。
- 任意登录成功清理两个失败桶与当前挑战。
- IP 本期不参与 Key、计数或封禁，只允许脱敏审计。

## 固定 RT 会话 v4

### RT 会话 Key

```text
ait:<env>:auth:session:v4:rt:<HMAC(refreshToken)>
```

类型为 Redis Hash，字段严格限定为：

```text
userId
publicId
csrfHash
email
phone
deviceHash
```

- 原始 NanoID38 RT 只交给客户端，Redis Key 只使用 RT HMAC。
- 整个 RT Key 使用三小时滑动 TTL，没有绝对会话上限。
- 不保存 `schemaVersion`、`sid`、`familyHash`、`refreshHash`、`passwordVersion`、时间字段、`accountStatus`、`membershipTier` 或 `quotaBalanceMinor`。
- Key 中的 `v4` 是结构版本；Redis TTL 是唯一过期时间来源。

### 用户 RT 反向索引

```text
ait:<env>:auth:session:v4:user-rts:<userId>
```

类型为 Redis Hash：

```text
Field = <tokenHash>
Value = <完整的 v4 RT Key>
```

Redis 7.4.9 为每个 `tokenHash` 字段设置独立三小时 TTL。强制保持：

```text
真实 rt:<tokenHash> Key TTL
= user-rts:<userId> 当前 tokenHash 字段 TTL
= 用户索引 Hash Key 的最大字段 TTL
```

登录、AT 已过期时的同请求续签和 bootstrap 均在 Lua 中使用 Redis 服务器时间计算同一个 `expiresAt`：

```text
PEXPIREAT  rtKey       expiresAt
HPEXPIREAT userRtsKey  expiresAt FIELDS 1 tokenHash
PEXPIREAT  userRtsKey  expiresAt
```

只续当前 RT 字段，其他设备字段 TTL 不变。当前字段被续到完整三小时后，它自然成为最大 TTL，因此索引 Hash Key 同步续到三小时。

### 原子操作

| Lua | 职责 |
| --- | --- |
| `create_refresh_session.lua` | 原子创建六字段 RT Hash 和索引字段；过期字段不计入最多十个会话限制 |
| `validate_access_session.lua` | 普通请求只读校验 RT、设备、CSRF、索引成员和全部正数 TTL，不延长任何 TTL |
| `validate_access_session_with_preauth.lua` | 在同一次只读校验中额外验证已认证 PreAuth 的用户作用域、设备和 RT Session 绑定 |
| `validate_refresh_session.lua` | 校验 RT、设备、CSRF 与已有索引字段后续三处 TTL；不得恢复缺失索引字段 |
| `update_refresh_session_csrf.lua` | bootstrap 校验 RT 和设备，更新同一 RT 的 `csrfHash` 并续三处 TTL |
| `revoke_refresh_session.lua` | 删除当前 RT 和索引字段，用最多十个剩余字段的 `HPTTL` 重算索引 Key TTL |
| Pipeline + 多 Key `UNLINK` | 读取 v4 `HVALS` 和迁移期 v3 字段后，一次批量删除全部真实 RT 与两版用户索引 |

固定 RT 不做轮换，因此不存在 family、active、used、轮换竞争和旧 RT 重放墓碑。该取舍的安全边界依赖 HttpOnly Cookie、AndroidKeyStore、安装设备 HMAC、CSRF 和三小时滑动 TTL。

## TOTP 域

```text
ait:<env>:auth:totp:v2:login-flow:<HMAC(totpFlowToken)>
ait:<env>:auth:totp:v2:used-step:<HMAC(userId,timeStep)>
ait:<env>:auth:totp:v2:setup:<HMAC(userId)>
ait:<env>:auth:totp:v2:step-up-flow:<HMAC(loginFlowToken)>
ait:<env>:auth:totp:v2:step-up-proof:<HMAC(stepUpToken)>
```

- `login-flow` 是第一因子通过后的五分钟 Hash，只保存用户 ID、设备 HMAC、失败次数和时间边界；不保存 TOTP 密钥。
- `used-step` 是九十秒 `SET NX` 防重放标记，同一用户的同一匹配时间片只能被一个成功请求领取。
- `setup` 每用户最多一个，保存 setupToken HMAC、设备 HMAC、待确认密钥密文、生成时的数据库 TOTP 状态快照、动作、失败次数和十分钟到期时间；重新申请直接覆盖旧值，确认时使用该快照执行 CAS，禁止过期流程覆盖中途已变更的密钥。
- `step-up-flow` 把当前用户验证码流程绑定到用户、设备和 ENABLE/ROTATE/DISABLE 动作；验证码原子消费后提升为五分钟 `step-up-proof`。
- `step-up-proof` 只能由对应用户、设备和动作消费一次，不能把开启凭证改用于关闭；轮换或关闭时当前 TOTP 连续失败五次会原子销毁该凭证，用户必须重新复验第一因子。
- TOTP Redis 异常全部 Fail Closed；数据库正式密钥不进入通用用户缓存，登录和管理操作直接读取 PostgreSQL 当前状态。
- Redis 仅暂存 AES-256-GCM 密文，不保存原始 32 字节密钥、Base32 展示值、`otpauth` URI 或六位动态码。

## 找回密码域

```text
ait:<env>:auth:password-reset:v2:flow:<HMAC(resetFlowToken)>
ait:<env>:auth:password-reset:v2:challenge:<HMAC(challenge)>
ait:<env>:auth:password-reset:v2:email-code:<HMAC(flowToken)>
ait:<env>:auth:password-reset:v2:phone-code:<HMAC(flowToken)>
ait:<env>:auth:password-reset:v2:send-risk:<HMAC(device)>
ait:<env>:auth:password-reset:v2:target-send:<HMAC(emailOrPhone)>
ait:<env>:auth:password-reset:v2:verify-risk:<HMAC(device)>
ait:<env>:auth:password-reset:v2:block:<HMAC(device)>
ait:<env>:auth:password-reset:v2:forget:<HMAC(forgetToken)>
```

- reset flow 空闲十分钟、绝对三十分钟。
- 发送冷却六十秒，设备和目标固定窗口五分钟、最多五次。
- 设备第六次发送封禁两小时；目标超限只返回统一提示，不封禁账号。
- 第十一次验证码失败封禁设备两小时。
- forgetToken 固定 TTL 五分钟、不续签、绑定设备，并以 claim/consume/release 防止并发消费。
- 密码数据库事务提交后，通过 Pipeline 有界读取 v4 `HVALS` 和迁移期 v3 字段，再用多 Key `UNLINK` 删除全部 RT 和两版索引；不扫描全库，也不使用显式 Java/Lua `for`。
- `password_version` 只保留在 PostgreSQL 并在重置时递增，不写入 AT/RT，也不参与会话校验。

## 一致性边界

- PostgreSQL 与 Redis 不使用分布式事务。
- 密码先在 PostgreSQL 本地事务中提交，再消费 forgetToken、撤销全部 RT、异步发送提醒邮件。
- RT Pipeline 撤销失败同步重试三次；Pipeline 不具备事务原子性，仍失败时返回 `SESSION_REVOCATION_FAILED`，不得错误报告全部设备已下线；TTL 仅提供兜底收敛，不构成强一致保证。
- AT 本身不写入 Redis；但普通 API 每次都先校验 RT Session，因此密码重置、TOTP 变更或退出全部设备撤销 RT 后，旧 AT 会在下一次请求立即失效。
- TOTP 开启、轮换和关闭同样先提交 PostgreSQL，再删除待确认项并批量撤销全部固定 RT；缓存或会话删除失败不回滚已经提交的 TOTP 状态，TTL 与后续重试只提供尽力收敛。
