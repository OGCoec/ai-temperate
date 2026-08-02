# ai-temperate 认证模块交接文档

## 1. 当前中断点

认证模块代码已按“固定 Refresh Token + Redis 7.4 Hash 字段 TTL”v4 方案完成第一阶段改造：

- 登录时只生成一次 NanoID38 RT。
- 刷新 AT 时 RT 原文和 HMAC 摘要都不变化，只把 RT 与反向索引 TTL 恢复到三小时。
- 普通刷新保持 CSRF 不变；H5 bootstrap 才生成新 CSRF。
- AT 已删除 `sid` 和 `passwordVersion` Claims。
- Redis 会话已删除 family、active、used 与 RT 轮换结构。
- 密码重置后按用户反向索引物理删除全部 RT。
- Android 刷新响应没有 RT 时，不会清空 KeyStore 中的固定 RT。

本轮严格处于“先交付代码”阶段：没有运行 Maven、前端构建、测试、依赖分析、安全扫描，也没有连接 PostgreSQL、Redis、RabbitMQ、SMTP、短信或 Turnstile。当前内容不能表述为“构建成功”或“测试通过”。

## 2. 代码位置

- 后端：`C:\Users\damn\Desktop\ai-temperate`
- 前端：`C:\Users\damn\Desktop\ai-temperate\fornted`
- 只读参考：`C:\Users\damn\Desktop\shopping`
- 只读参考：`C:\Users\damn\Desktop\uniapp\myuniapp`

参考项目没有直接修改。

## 3. 最终认证规则

### 3.1 通用边界

- 所有认证请求携带安装级 UUID v4：`X-Device-Installation-Id`。
- Android 卸载或清除数据、H5 清除站点数据后重新生成安装 UUID。
- Redis 只保存服务端 HMAC-SHA256 摘要，不把原始 UUID、Token、邮箱、手机号或验证码放入 Key。
- IP 本期不参与设备哈希、计数和封禁，只允许脱敏审计及 Turnstile `remoteip`。
- Java `long/Long` 的 HTTP JSON 输出由 `HttpJsonLong2StringConfiguration` 转成字符串。
- `GlobalExceptionHandler implements AuthExceptionHandler` 统一处理认证异常和数据库唯一索引异常。
- Controller 使用中文 `@Tag` 和 `@Operation`，供 Swagger/OpenAPI/Apifox 调试。

### 3.2 注册

- 注册必须同时填写规范化邮箱与国际手机号。
- 唯一性使用两层保证：先一次数据库查询同时取得手机号、邮箱冲突标记；最终由 PostgreSQL 唯一索引兜底。
- 业务判断顺序为手机号优先、邮箱其次；对外只返回统一冲突提示。
- 同一次请求总冲突计数只加一次，手机号与邮箱子计数分别记录。
- 固定五分钟冲突窗口，前五次累计，第六次封禁注册域设备两小时。
- registerToken 为 NanoID38，空闲十分钟、绝对三十分钟；只有注册拦截器白名单 URL 校验成功才续空闲 TTL。
- 每个流程只做一次 Turnstile；客户端 `humanVerified=true` 不作为服务端依据。
- 邮箱与短信分别具有六十秒冷却、固定五分钟发送窗口，第六次触发注册域封禁两小时。
- 双验证码一次提交，必须同时正确；任意一个错误则整体失败。
- 单码错误五次后作废；组合验证总失败第十一次封禁两小时。
- 主用户 UniApp 与 Spring Boot 统一使用 \`SHOPPING_V1\` 五档密码强度；最低接受“中”，且不得超过 72 个 UTF-8 字节。72 字符输入上限之外仍以 UTF-8 字节数作为最终门禁。
- 注册成功不签发 AT/RT，不自动登录，返回 `nextAction=LOGIN`。
- 新用户在 `user_membership_quota` 中初始化为 `membershipTier=FREE`，额度数据库值为 `5000`，按固定缩放比例 100 表示实际额度 `50.00`；额度周期开始时间为空，结束时间由注册业务写入当前 UTC 时间。

### 3.3 登录

- `LoginStrategyRegistry` 通过 Spring 注入 `Map<String, LoginStrategy>`，转换为不可变枚举 Map。
- 三种策略：`PASSWORD`、`EMAIL_CODE`、`SMS_CODE`。
- 密码登录支持邮箱加密码、国际区号及手机号加密码，不使用 Turnstile。
- 验证码登录每个流程只验证一次 Turnstile。
- 账号不存在时返回统一发送提示；不存在的邮箱发送“账号未注册”邮件，不发验证码；不存在的手机号不发短信。
- 密码失败桶与验证码失败桶独立，固定五分钟窗口；各桶第六次失败封禁整个登录域两小时。
- 任意登录成功清理两个失败桶和挑战状态。

### 3.4 固定 RT、AT 与 CSRF

AT：

```text
JWT TTL = 10 分钟
Claims = sub(publicUserId), jti(NanoID38), ver(2), iat, exp
```

AT 不含 `sid`、`passwordVersion`、邮箱、手机号、会员等级或额度字段。受保护业务请求验证 JWT 后，根据 publicUserId 查询 `userloginidentity + user_profile`，确认账号仍为 ACTIVE；不查询 Redis 会话或 `user_membership_quota`。退出或密码重置后，旧 AT 最长继续有效十分钟。

固定 RT Key：

```text
ait:<env>:auth:session:v4:rt:<HMAC(refreshToken)>
```

RT Hash 严格只有六个字段：

```text
userId
publicId
csrfHash
email
phone
deviceHash
```

RT Hash 不保存 `accountStatus`、`membershipTier` 或 `quotaBalanceMinor`；刷新和 bootstrap 从 `userId` 重新查询 `userloginidentity + user_profile`，不连接 `user_membership_quota`。

个人中心使用独立的每用户 Redis String 缓存展示资料，Key 中的内部 ID 由 AES-256-KWP 生成确定性
密文标识，Value 是带版本的明文 JSON。该缓存不属于 Refresh Session，不进入认证上下文，也不能作为
额度预扣或结算依据；详细边界见 `docs/operations/user-profile-cache-and-model-catalog.md`。

用户反向索引：

```text
ait:<env>:auth:session:v4:user-rts:<userId>
Field=<tokenHash>
Value=<完整的 v4 RT Key>
```

- 每个索引字段使用 Redis 7.4 独立三小时 TTL。
- 整个用户索引 Hash Key TTL 始终等于当前字段最大 TTL。
- 登录、普通刷新和 bootstrap 通过 Lua 同时设置真实 RT Key、当前索引字段和索引 Hash Key 的过期时间。
- 普通刷新校验 RT、设备、CSRF，续三处 TTL，只签发新 AT；RT 与 CSRF 均不改变。
- H5 必须重写相同 RT Cookie 的 `Max-Age`；Android 不覆盖 KeyStore 中的 RT。
- bootstrap 仅用于 H5，新生成 CSRF，RT 不变，旧 CSRF 立即失效。
- 当前设备退出删除当前 RT 与索引字段，并有界重算索引最大 TTL。
- 密码重置在数据库提交后，通过 Pipeline 多 Key `UNLINK` 批量撤销 v4 会话，并在迁移窗口内清理 v3 索引；Pipeline 非原子，失败依赖有限重试和 TTL 兜底，不宣称强一致。
- 迁移期 v3 索引仍是 `Field=tokenHash, Value="1"`，v4 索引改为 `Field=tokenHash, Value=完整 RT Key`；新会话只写 v4。
- 单用户最多十个 RT 会话，第十一个登录返回受控错误，不自动踢旧会话。
- 固定 RT 不再具备轮换型旧 Token 重放检测；安全边界依赖 HttpOnly、AndroidKeyStore、设备 HMAC、CSRF 和三小时滑动 TTL。

### 3.5 找回密码

- 邮箱或短信二选一。
- resetFlowToken 为 NanoID38，空闲十分钟、绝对三十分钟。
- 每个流程只验证一次 Turnstile。
- 发送冷却六十秒，设备与目标分别限制固定五分钟最多五次。
- 设备第六次发送封禁找回域两小时；目标超限只返回统一提示，不封禁账号。
- 验证失败第十一次封禁设备两小时。
- 验证成功签发固定 TTL 五分钟、不可续签、一次性、绑定设备的 forgetToken。
- 新密码与确认密码必须一致，不要求旧密码，也允许与当前密码相同。
- PostgreSQL 原子更新密码哈希并执行 `password_version + 1`；该版本不进入 AT/RT。
- 数据库提交后通过 `user-rts:<userId>` 有界读取并物理删除全部 RT，不扫描全库。
- 全会话撤销同步最多重试三次；仍失败返回 `SESSION_REVOCATION_FAILED`，不得报告全部设备已下线。
- 密码变更提醒邮件异步发送，失败不回滚密码。
- 找回成功不自动登录，返回 `nextAction=LOGIN`。

## 4. 后端调用结构

```text
Interceptor -> Controller -> Service 接口 -> ServiceImpl -> Mapper -> Mapper XML -> PostgreSQL
                                               |
                                               -> Store 接口 -> RedisStoreImpl -> Lua -> Redis
```

主要入口：

- 注册：`ai-temperate-web/src/main/java/com/example/temperate/web/auth/registration/controller/RegistrationController.java`
- 登录：`ai-temperate-web/src/main/java/com/example/temperate/web/auth/login/controller/LoginController.java`
- 会话：`ai-temperate-web/src/main/java/com/example/temperate/web/auth/session/controller/SessionController.java`
- 找回密码：`ai-temperate-web/src/main/java/com/example/temperate/web/auth/passwordreset/controller/PasswordResetController.java`
- 全局异常：`ai-temperate-web/src/main/java/com/example/temperate/web/auth/api/GlobalExceptionHandler.java`
- Redis Key：`ai-temperate-common/src/main/java/com/example/temperate/common/redis/key/RedisKeyFactory.java`
- 会话 Store：`ai-temperate-service/src/main/java/com/example/temperate/service/auth/session/refresh/store/impl/RedisRefreshSessionStore.java`
- 会话 Lua：`ai-temperate-service/src/main/resources/lua/auth-session`

Service 采用接口加 `Impl`，调用方依赖接口；使用构造器注入和 `final` 字段。

## 5. 前端结构

页面：

- `fornted/pages/auth/login.vue`
- `fornted/pages/auth/register.vue`
- `fornted/pages/auth/password-reset.vue`

公共层：

- API：`fornted/common/auth/auth-api.js`
- single-flight 请求层：`fornted/common/auth/http-client.js`
- 安装 UUID：`fornted/common/auth/device-installation.js`
- AndroidKeyStore：`fornted/common/auth/android-keystore.js`
- 会话内存与安全存储：`fornted/common/auth/session-vault.js`
- 密码规则：`fornted/common/auth/password-policy.js`
- 国家与区号：`fornted/common/auth/phone-countries.js`
- Turnstile：`fornted/components/auth/auth-turnstile.vue`

普通 refresh 只替换内存 AT 并保留原 RT、原 CSRF；bootstrap 替换 AT 与 CSRF 并保留 RT。并发业务请求只允许一次 refresh，其余请求排队等待。

## 6. 本地依赖与环境变量

当前约定：

- PostgreSQL：`5431`
- Redis：`6378`，目标版本 Redis 7.4.9
- 本地后端：`https://localhost:6655`，激活 `local-https` Profile 后仅提供 HTTPS
- 本地 H5：`https://localhost:3000`
- H5 公网入口：`https://niko000o.site`，目标由 Cloudflare Pages 托管
- API 公网入口：`https://api.niko000o.site`

本地 HTTPS 证书保存在 `%USERPROFILE%\.ai-temperate\certs`，不进入项目仓库：

```text
local-https.p12              Spring Boot 与 H5 Vite 使用的证书和私钥
local-https.pem              Windows 与 cloudflared 使用的公开证书
local-https.password.dpapi   当前 Windows 用户保护的 PKCS12 密码
```

P12 与 PEM 是同一张证书的不同表示。完全退出 Antigravity 和 HBuilderX 后，通过项目根目录 `start-local-https-dev.bat` 重新打开两个 IDE，启动器会把本地 HTTPS 环境显式注入到 IDE 子进程，后续 Run/Debug 子进程才会继续继承。若 Antigravity 或 Codex 已经在运行，可以完全退出 HBuilderX 后执行 `start-local-https-dev.bat -HBuilderXOnly`，只为 HBuilderX 重新建立本地 HTTPS 进程环境。H5 和后端复用同一张本地证书；Android 真机通过 Cloudflare 公网证书访问，不关闭 SSL 校验。

本地启动器会把 CORS 白名单补齐为 `https://localhost:3000`、`https://niko000o.site`，把 Turnstile hostname 白名单补齐为 `localhost`、`niko000o.site`，并保留已有合法条目。这些覆盖只存在于两个 IDE 的进程树中。

公网 H5 正从根域名直连 HBuilderX/Vite 迁移到 Cloudflare Pages。旧 `frontend` Tunnel 暂时保留作回滚；新增的 `frontend-dev` Profile 只允许使用独立 Tunnel ID 映射 `dev.niko000o.site`。具体发布和安全例外收口步骤见 `docs/operations/frontend-public-deployment.md`。

至少需要：

```text
POSTGRES_PASSWORD
REDIS_PASSWORD
RABBITMQ_PASSWORD
MAIL_PASSWORD
AUTH_JWT_SECRET_BASE64
AUTH_HMAC_SECRET_BASE64
AUTH_SESSION_HMAC_SECRET_BASE64
TURNSTILE_SITE_KEY
TURNSTILE_SECRET_KEY
TURNSTILE_ALLOWED_HOSTS
CORS_ALLOWED_ORIGINS
REGISTRATION_MAIL_FROM
ALIYUN_SMS_ACCESS_KEY_ID
ALIYUN_SMS_ACCESS_KEY_SECRET
ALIYUN_SMS_SIGN_NAME
ALIYUN_SMS_TEMPLATE_CODE
```

生产密钥禁止写入 YAML、代码、日志和交接文档。

## 7. 第二阶段待确认验证

在用户明确批准前不要执行。拟议验证范围应单独说明命令、依赖服务和可能写入的数据：

1. Maven 单元与结构测试，不连接外部服务。
2. Redis 7.4.9 Testcontainers 集成测试，写入并清空隔离容器内测试数据。
3. PostgreSQL 隔离库唯一索引与密码重置事务测试。
4. Spring 上下文、CORS、CSRF 与 OpenAPI 测试。
5. HBuilderX H5/Android 构建和 AndroidKeyStore 真机验证。
6. 用户参与的注册、三种登录、固定 RT 刷新、bootstrap、退出与找回密码联调。

当前全部属于“尚未执行”，不能声称通过。
