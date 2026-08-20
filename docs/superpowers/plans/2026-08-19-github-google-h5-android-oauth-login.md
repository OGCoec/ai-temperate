# H5 与 Android GitHub/Google 登录实施计划

> 本文完整替换旧 H5-only 计划。实施前代码基线为 `main@29b65b8340cb4b0795bc9de309381cdede24a304`，备份标签为 `pre-oauth-backup-2026-08-19`。

## 目标与边界

- H5 Google/GitHub 使用 Spring 管理的 Browser Authorization Code Flow。
- H5 Google 固定 `prompt=select_account`。
- Android Google 默认使用 Credential Manager 原生账号选择器，关闭自动选择；无 GMS 时由用户明确选择浏览器兜底。
- Android GitHub 使用系统浏览器与 HTTPS App Link 返回。
- Android 包名固定为 `site.niko000o.aitemperate`，测试域名固定为 `niko000o.site`。
- 本期不实现 Microsoft、第三方解绑、安全评分或设置密码页面。
- 不创建外部身份子表；一个邮箱账号可以同时使用密码、Google 与 GitHub。

## 数据模型

`userloginidentity` 一行保存：

```text
registration_source SMALLINT NOT NULL DEFAULT 0
github_subject      VARCHAR(255) NULL
google_subject      VARCHAR(255) NULL
email               VARCHAR(254) NOT NULL
email_verified      BOOLEAN NOT NULL DEFAULT FALSE
phone               VARCHAR(20) NULL
password_hash       VARCHAR(255) NULL
```

约束：

- `LOWER(email)` 唯一。
- 非空 `github_subject`、`google_subject`、`phone` 分别唯一。
- `registration_source` 只记录首次注册来源：`STANDARD=0`、`GITHUB=1`、`GOOGLE=2`，后续绑定不得修改。
- Google 使用 OIDC `sub`；GitHub 使用数字 `user.id` 字符串；邮箱不能替代 Subject。

## 账号解析与事务

Provider 证明统一转换为：

```text
provider
providerSubject
verifiedEmail
emailVerified
proofType
```

固定顺序：

1. 先按 Provider Subject 查询。
2. Subject 已绑定时始终进入原账号，不用 Provider 当前邮箱改绑账号。
3. Subject 未绑定时必须取得 Provider 已验证邮箱。
4. 按规范化邮箱查询本地账号。
5. 邮箱存在且当前 Provider Subject 为空时自动绑定，不要求密码、邮箱验证码或额外手机号证明。
6. 邮箱不存在时必须先验证手机号再注册。
7. 已有账号缺手机号时也进入补验手机号流程。
8. Subject 已被不同账号占用返回 409，禁止覆盖或转移。
9. 同 Subject 并发绑定幂等；不同 Subject、邮箱或手机号冲突由 PostgreSQL 唯一索引最终裁决。
10. 原账号密码、TOTP、手机号和另一个 Provider Subject全部保留。

手机号证明完成后，独立本地事务重新查询 Subject、邮箱和手机号并完成绑定或创建；新账号写入已验证邮箱、已验证手机号、对应 Subject、首次来源和 `password_hash=NULL`，使用现有默认昵称及免费额度规则。事务提交后再清理缓存并调用现有 `LoginCompletionService`，启用 TOTP 的账号返回 `TOTP_REQUIRED`。

## API

```text
POST /api/auth/oauth2/start
GET  /api/auth/oauth2/authorization/{provider}
GET  /api/auth/oauth2/code/{provider}
POST /api/auth/oauth2/google/native/complete
GET  /api/auth/oauth2/flow/status
POST /api/auth/oauth2/phone/start
POST /api/auth/oauth2/phone/turnstile
POST /api/auth/oauth2/phone/send
POST /api/auth/oauth2/phone/verify
POST /api/auth/oauth2/complete
POST /api/auth/oauth2/cancel
```

`/start` 返回：

- H5：`BROWSER_REDIRECT + authorizationUrl`，原始 Flow Token 只写 HttpOnly Cookie。
- Android Google：`GOOGLE_NATIVE + NanoID38 Flow Token + nonce + Server Client ID`。
- Android GitHub/Google 浏览器兜底：`BROWSER_REDIRECT + authorizationUrl + NanoID38 Flow Token`。

状态：

```text
PROVIDER_PENDING
PHONE_REQUIRED
HUMAN_VERIFICATION_REQUIRED
CODE_READY
READY_TO_COMPLETE
TOTP_REQUIRED
AUTHENTICATED
FAILED
EXPIRED
```

## Browser OAuth 安全

- Provider 换码、Google JWS 验证和 GitHub资料读取使用 Spring Security OAuth2 Client。
- NanoID32 `state`，Redis 只保存用途隔离 HMAC，Lua 原子一次性消费。
- PKCE S256；Google 额外使用 OIDC nonce。
- H5 使用 `Secure + HttpOnly + SameSite=Lax` 十分钟握手 Cookie 与 `SameSite=Strict` 三十分钟 Flow Cookie。
- Callback 不要求 CSRF Header；其他 H5 POST 保留现有 Cookie CSRF、PreAuth 与风险门禁。
- GitHub scope 固定 `read:user user:email`，仅接受 `primary=true && verified=true` 邮箱。
- Google scope 固定 `openid profile email`，验证签名、issuer、audience、有效期、nonce、`sub` 与 `email_verified=true`。
- Provider Code、Access Token、ID Token、state、Flow Token、邮箱或手机号不得进入回跳 URL、日志或长期存储。

## Android Google

UTS 插件 `ait-google-signin` 固定依赖：

```text
androidx.credentials:credentials:1.6.0
androidx.credentials:credentials-play-services-auth:1.6.0
com.google.android.libraries.identity.googleid:googleid:1.2.0
minSdkVersion: 21
```

插件只在用户点击后打开账号选择器，关闭自动登录，先列出已授权账号，无结果时再列出全部设备账号；成功只返回内存中的短时 ID Token。服务端在验证 JWS 声明后用 Lua 一次性消费与 Flow 绑定的 nonce。无 GMS、无凭据或系统异常时显示浏览器兜底入口；用户取消不显示错误警告。

## Android GitHub 与 App Link

```text
App /start
-> Flow Token 写入 AndroidKeyStore
-> 系统浏览器打开一次性 launch URL
-> Provider 回调消费 code/state/PKCE
-> https://niko000o.site/app/oauth-return
-> App Link 唤醒 App
-> App.vue onShow 查询 Flow 状态
```

App Link：

- Package：`site.niko000o.aitemperate`
- Scheme/Host/Path：`https://niko000o.site/app/oauth-return`
- `android:autoVerify=true`
- Worker 托管 `/.well-known/assetlinks.json`。
- Debug 环境使用仓库外自有 JKS 的 SHA-256；正式发布前替换为 Release/Play App Signing 指纹并移除 Debug 信任。
- App Link 未验证时页面只提示用户手动返回 App，App 仍通过 `onShow` 恢复 KeyStore Flow。

## 手机验证与风控

现有验证码状态机增加服务端固定用途 `OAUTH_PHONE`，客户端不能提交 purpose。复用 E.164、Turnstile、SMS/WhatsApp、验证码摘要、RabbitMQ 投递、一次性消费与监控；OAuth 验证成功只证明当前 Flow 拥有手机号，随后才执行账号事务。

- Turnstile action：`oauth_phone`。
- Flow 空闲十分钟、绝对三十分钟；验证码五分钟。
- 发送冷却六十秒；五分钟最多五次有效发送。
- 第六次发送或冷却绕过封禁 Flow 主体与全局设备两小时。
- 手机号冲突五分钟超过五次同样封禁两小时。
- 单验证码最多错误五次。
- Redis 不可用 Fail Closed，不调用短信供应商。
- 手机号冲突统一返回 `OAUTH_PHONE_UNAVAILABLE`，不泄露所属账号。
- 更换手机号创建新的验证码 Flow 和 Challenge，旧 Turnstile 与验证码不能推进 OAuth。

## 前端与 Worker

- 登录页显示 Google、GitHub、密码、邮箱验证码和手机验证码。
- OAuth 手机页复用现有国家选择、手机号、Turnstile、SMS/WhatsApp、倒计时和验证码组件。
- Android OAuth Flow/TOTP Flow 使用现有 AndroidKeyStore 加密存储；Google ID Token 永不写 Storage/KeyStore/URL/日志。
- Worker 只代理精确 OAuth 路由；只允许 Google、GitHub 官方授权端点和两个固定本站返回页的 302。
- Worker 托管固定 App 返回页与 `assetlinks.json`；返回页 `no-store` 且不包含流程材料。
- CORS 只允许精确 H5 Origin，新增 `X-OAuth-Flow-Token` 与 OAuth 手机子流程 Header；Android Native 不依赖 Origin。

环境变量：

```text
GOOGLE_OAUTH_CLIENT_ID
GOOGLE_OAUTH_CLIENT_SECRET
GOOGLE_ANDROID_SERVER_CLIENT_ID
GITHUB_OAUTH_CLIENT_ID
GITHUB_OAUTH_CLIENT_SECRET
OAUTH_PUBLIC_BASE_URL
OAUTH_H5_RETURN_URL
OAUTH_ANDROID_RETURN_URL
```

## 分阶段验收

第一阶段只实现代码与测试代码，不执行 Maven/Node 测试、编译、打包、外部连接或真机联调。第二阶段取得用户明确授权后再执行：

1. Spring、Mapper、Redis Lua、Worker 与前端测试。
2. Google Consent Screen Testing/Test Users 与 GitHub OAuth App 配置。
3. 在 `C:\Users\damn\.android-keys\ai-temperate-debug.jks` 生成仓库外 Debug JKS。
4. 写入 Debug SHA-256 并部署 Worker `assetlinks.json`。
5. 用 HBuilderX 打包含 UTS 原生依赖的自定义 Debug APK，安装真机。
6. 验证 H5 两种 Provider、Android Google 账号列表、无 GMS 兜底、GitHub App Link、手动返回、同邮箱三种登录、Provider 改邮箱仍按 Subject、手机号冲突、TOTP 与敏感材料不落日志/URL。

普通 DCloud 标准基座不作为最终验收，因为其包名、签名和原生依赖均不符合本计划。
