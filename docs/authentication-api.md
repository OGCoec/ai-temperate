# 认证模块 API 约定

## 公共请求头

| Header | 说明 |
| --- | --- |
| `X-Device-Installation-Id` | 客户端首次安装生成并持久化的 UUID v4；Android 卸载或清除数据、H5 清除站点数据后重新生成 |
| `X-Client-Platform` | `H5` 或 `ANDROID`，缺省按 H5 处理 |
| `X-Register-Token` | 注册流程 NanoID38 |
| `X-Login-Flow-Token` | 验证码登录流程 NanoID38 |
| `X-OAuth-Flow-Token` | Android Google/GitHub 登录的 NanoID38 流程凭据；H5 改用路径隔离的 HttpOnly Cookie |
| `X-OAuth-Phone-Flow-Token` | Android OAuth 补手机号子流程凭据；只能与外层 OAuth Flow 和当前设备组合使用 |
| `X-TOTP-Flow-Token` | Android 第一因子通过后使用的五分钟 TOTP 登录挑战；H5 改用路径隔离的 HttpOnly Cookie |
| `X-Reset-Flow-Token` | 找回密码流程 NanoID38 |
| `X-Forget-Token` | 验证码通过后签发的五分钟一次性密码重置凭证 |
| `X-Turnstile-Challenge` | 与 Turnstile `cData` 绑定的 NanoID38 challenge |
| `X-Turnstile-Attempt-Id` | 客户端为单次 Turnstile 提交生成的有限长度关联标识；只用于排障，不是凭据或幂等依据 |
| `X-CSRF-Token` | H5 所有非安全方法都从 `XSRF-TOKEN` Cookie 读取并提交；Android 调用 refresh、logout 时从 KeyStore 提交；bootstrap 不提交旧 CSRF |

服务端对安装 UUID、流程 Token、RT、CSRF、邮箱和手机号等敏感标识执行带服务端密钥的 HMAC-SHA256。Redis Key 和日志都不保存原文。

`cf_clearance` 仍由 Cloudflare 与浏览器或受控 WebView 管理，不属于应用签发的 AT、RT、CSRF，也不参与本认证存储协议。
Turnstile 回调 Token 是一次性短期响应值，不存放在应用 Cookie 中；`challenges.cloudflare.com` 的存储列表为空不能单独判定验证失败。

所有实际到达后端的 `/api/auth/**` 响应都携带 `X-Trace-Id`；若响应只有 `CF-Ray` 或 `cf-mitigated: challenge` 而没有该追踪号，说明请求可能在 Cloudflare 边缘被终止。浏览器可读取这些响应头，用于把前端尝试、边缘事件、Siteverify 和 Redis 状态阶段关联起来；服务端内部诊断原因不会进入错误响应体。

认证标识格式校验规则：

- 注册、登录和找回密码页面使用同一套前端邮箱格式规则提前拦截明显无效输入；所有 API 仍在后端重新校验，禁止依赖前端校验结果。
- 邮箱后端统一执行长度、空白字符、`@`、本地部分和域名标签校验，并规范化为小写形式。
- 手机号后端使用 Google libphonenumber 按 `countryIso2` 解析，先验证号码属于对应地区，再规范化为 E.164。
- 手机号类型仅允许 `MOBILE` 和 `FIXED_LINE_OR_MOBILE`；明确的座机、VoIP、免费号码及其他非手机类型返回输入错误。
- libphonenumber 不是号码存活检测，不能证明号码真实存在、可以接收短信或属于提交者，也不能保证识别全部虚拟运营商号码；号码所有权仍由验证码验证。

密码策略统一为 `SHOPPING_V1`：

- 等级为 `NONE`/`无`/`0`、`WEAK`/`弱`/`1`、`MEDIUM`/`中`/`2`、`STRONG`/`强`/`3`、`VERY_STRONG`/`很强`/`4`。
- 空值或长度不超过 6 为“无”；长度至少 7 且仅包含数字、仅小写字母或仅大写字母为“弱”。
- 分别检测小写、大写、数字和 `[!@#$%^&*(),.?\":{}|<>]` 四类；长度至少 9 且命中四类为“很强”，命中三类为“强”，其他非前两档值为“中”。
- 最低可接受等级为 `MEDIUM`；另外必须不超过 BCrypt 的 72 个 UTF-8 字节边界。字节超限不改变显示等级，但会阻止提交和哈希。
- UniApp 页面和 API 封装层均在发请前检查；Spring Boot 不信任客户端结果，使用同一契约重新计算。
- 密码通过 HTTPS JSON 传输，不增加应用层对称加密或公钥接口；数据库只保存 `PasswordEncoder` 生成的带算法标识随机加盐哈希。

统一错误响应：

```json
{
  "code": "STABLE_ERROR_CODE",
  "message": "面向客户端的稳定提示",
  "timestamp": "2026-07-15T00:00:00Z"
}
```

Java `Long` 类型在 HTTP JSON 中统一序列化成字符串，防止超过 JavaScript 安全整数边界。对外用户 ID 使用固定 11 字符 Base64URL，不返回数据库内部 ID。

## 注册

| Method | Path | 请求与行为 |
| --- | --- | --- |
| POST | `/api/auth/register/start` | 提交邮箱、`countryIso2`、本地手机号；规范化并检查手机号和邮箱唯一性后签发 registerToken |
| GET | `/api/auth/register/status` | 恢复注册状态；只有注册拦截器白名单 URL 校验通过后才续空闲 TTL |
| POST | `/api/auth/register/turnstile` | 服务端校验 Turnstile token、action、hostname 和重放状态 |
| POST | `/api/auth/register/codes/email/send` | 人机验证通过后手动发送邮箱验证码 |
| POST | `/api/auth/register/codes/sms/send` | 人机验证通过后手动发送短信验证码 |
| POST | `/api/auth/register/codes/verify` | 一次提交邮箱与短信验证码，必须同时正确 |
| POST | `/api/auth/register/complete` | 提交新密码与确认密码，事务入库并消费注册流程 |

`/register/complete` 的 JSON 保持 `{ password, passwordConfirmation }`，不接收客户端强度字段。低于“中”或超过 72 个 UTF-8 字节时返回 HTTP 400 + `PASSWORD_STRENGTH_INSUFFICIENT`，且不调用哈希器或写入 Mapper。

注册成功不自动登录：

```json
{
  "registered": true,
  "nextAction": "LOGIN"
}
```

## 登录

| Method | Path | 请求与行为 |
| --- | --- | --- |
| POST | `/api/auth/login/password` | 支持邮箱加密码、国际区号及手机号加密码；不经过 Turnstile |
| POST | `/api/auth/login/code/start` | `strategyType=EMAIL_CODE` 或 `SMS_CODE`，创建验证码登录流程 |
| POST | `/api/auth/login/code/turnstile` | 每个验证码登录流程只验证一次 Turnstile |
| POST | `/api/auth/login/code/send` | 统一返回“如果账号存在，验证码已经发送” |
| POST | `/api/auth/login/code/verify` | 校验邮箱码或短信码，成功后创建固定 RT 会话 |
| POST | `/api/auth/login/totp/verify` | 已启用 TOTP 时校验第一因子之后的六位动态码，成功后才创建固定 RT 会话 |

密码登录完成恒定成本哈希比对后，再检查当次明文强度及数据库策略元数据；验证码登录在验证码成功后执行同样的元数据检查。旧记录或策略不合格时返回 HTTP 409 + `PASSWORD_RESET_REQUIRED`，不签发 AT、RT 或 CSRF，也不按错误密码计入失败次数。

第一因子成功后统一进入登录完成边界：`totp_enabled=false` 才直接签发会话；启用 TOTP 时只返回
`status=TOTP_REQUIRED`、到期时间和剩余次数，不提前创建 AT、RT 或 CSRF。H5 的原始挑战只写入
`Path=/api/auth/login/totp` 的 HttpOnly Cookie；Android 响应体携带挑战并使用独立 AndroidKeyStore 密文保存。
TOTP 挑战有效期五分钟、最多失败五次，验证码接受当前三十秒时间片前后各一个时间片；同一用户的同一
匹配时间片通过 Redis 原子领取，禁止在并发登录或敏感操作中重放。

H5 登录前若缺少 CSRF Cookie，先调用 `GET /api/auth/csrf`。该接口返回 204，并初始化 JavaScript 可读的 `XSRF-TOKEN` 会话 Cookie。

## Google 与 GitHub OAuth 登录

| Method | Path | 请求与行为 |
| --- | --- | --- |
| POST | `/api/auth/oauth2/start` | 按 Provider、H5/Android 和交互模式创建短时 Flow；H5 不返回原始 Flow Token |
| GET | `/api/auth/oauth2/authorization/{provider}` | 消费 H5 Cookie 或 Android 一次性 launch ticket，并跳转固定 Provider 授权端点 |
| GET | `/api/auth/oauth2/code/{provider}` | 一次性消费 state，使用 PKCE 换码并跳转固定 H5/App 返回页 |
| POST | `/api/auth/oauth2/google/native/complete` | Android 上传 Credential Manager 的短时 Google ID Token；服务端验证 JWS 与 nonce 后立即消费 nonce |
| GET | `/api/auth/oauth2/flow/status` | 恢复 App Link、手动回到 App 或 H5 回调后的流程状态 |
| POST | `/api/auth/oauth2/phone/start` | 为当前 OAuth Flow 锁定规范化手机号并创建服务端固定 `OAUTH_PHONE` 子流程 |
| POST | `/api/auth/oauth2/phone/turnstile` | 使用 `oauth_phone` action 完成人机验证 |
| POST | `/api/auth/oauth2/phone/send` | 从服务端 Flow 读取锁定手机号并发送短信或 WhatsApp，客户端不能重新提交手机号 |
| POST | `/api/auth/oauth2/phone/verify` | 一次性消费验证码，只把手机号归属证明写入当前 OAuth Flow |
| POST | `/api/auth/oauth2/complete` | 事务内重新裁决 Subject、邮箱与手机号，随后签发正式会话或返回 `TOTP_REQUIRED` |
| POST | `/api/auth/oauth2/cancel` | 删除当前短时 OAuth Flow；Android 同时清除本地 KeyStore 流程材料 |

Google 使用 OIDC `sub`，GitHub 使用数字 `user.id`；Provider 已验证邮箱只在 Subject 未绑定时用于匹配本地账号。同邮箱密码账号会自动绑定对应 Subject，不要求再次输入密码或邮箱验证码；邮箱不存在或已有账号缺手机号时必须先完成 Turnstile 与手机验证码。`registration_source` 只记录首次来源，绑定第二种或第三种登录方式时不改变。

浏览器流程使用 NanoID32 state、PKCE S256、一次性浏览器握手，Google 额外校验 OIDC nonce；Android 原生 Google 不使用浏览器 state 或 App Link。Provider Code、Access Token、ID Token 和原始 Flow Token 不写入数据库、Redis 长期值、应用日志或固定回跳 URL。

OAuth 手机发送采用六十秒冷却、五分钟最多五次和两小时 Flow/全局设备封禁；手机号冲突统一返回 `OAUTH_PHONE_UNAVAILABLE`，不披露号码属于哪个账号。Redis 不可用时关闭发送通路，不调用短信供应商。

所有业务 Cookie 都不设置 `Domain`。生产普通 H5 通过
`https://niko000o.site/api/**` 的 Cloudflare Worker 同源入口访问后端，管理员 H5
通过 `https://admin.niko000o.site/api/admin/**` 访问同一个 Worker。Worker 原路径转发到
`api.niko000o.site`，浏览器只会把后端响应中的 Host-only Cookie 保存到当前前端 Host。

H5 登录成功响应不返回任何 Token：

```json
{
  "publicUserId": "AAAAAAAAJxE",
  "displayName": "用户",
  "refreshExpiresAt": "2026-07-15T03:00:00Z"
}
```

H5 同时接收：

```http
Set-Cookie: access_token=<AT>; Max-Age=600; Path=/api; Secure; HttpOnly; SameSite=Strict
Set-Cookie: refresh_token=<RT>; Max-Age=10800; Path=/api; Secure; HttpOnly; SameSite=Strict
Set-Cookie: XSRF-TOKEN=<CSRF>; Path=/; Secure; SameSite=Strict
```

Android 登录响应继续返回顶层 `accessToken`、`refreshToken`、`csrfToken`；三个值使用 AndroidKeyStore 管理的不可导出 AES-GCM 密钥一起加密后保存在应用私有存储中。

```json
{
  "publicUserId": "AAAAAAAAJxE",
  "displayName": "用户",
  "accessToken": "<JWT>",
  "refreshToken": "<fixed RT>",
  "csrfToken": "<CSRF>",
  "refreshExpiresAt": "2026-07-15T03:00:00Z"
}
```

## 当前用户 TOTP 管理

全部接口位于 `/api/users/me/security/totp`，用户身份只取自 Access Token，不接受客户端用户 ID。
所有响应使用 `private, no-store`。

| Method | Path | 请求与行为 |
| --- | --- | --- |
| GET | `/api/users/me/security/totp` | 只返回 `enabled`，不返回密钥或密文 |
| POST | `/reverification/password` | 当前密码复验后签发绑定用户、设备和动作的五分钟一次性 step-up token |
| POST | `/reverification/code/start` | 从数据库读取当前用户邮箱或手机号，开始邮箱码/短信码复验 |
| POST | `/reverification/code/turnstile` | 校验复验验证码流程的人机挑战 |
| POST | `/reverification/code/send` | 发送邮箱或短信验证码 |
| POST | `/reverification/code/verify` | 原子消费验证码并签发一次性 step-up token，不创建登录会话 |
| POST | `/setup/start` | 消费 ENABLE/ROTATE step-up token；生成 32 随机字节密钥，以密文暂存 Redis 十分钟并返回 Base32 与 `otpauth` URI |
| POST | `/setup/confirm` | 校验新认证器动态码后才以 CAS 写入密文并启用 TOTP |
| POST | `/disable` | 消费 DISABLE step-up token、校验当前 TOTP，再以单条 SQL 清空密文并关闭状态 |

客户端必须在本地从 `otpauth` URI 生成二维码，禁止把 URI 或 Base32 密钥发送给二维码网站。用户再次
申请新密钥时，Redis 中旧待确认项立即失效；数据库当前密钥在新动态码确认前始终保持生效。轮换和关闭
除密码/邮箱码/短信码复验外，还必须校验当前认证器动态码；当前动态码连续失败五次会销毁 step-up token，
必须重新完成第一因子复验。

开启、轮换或关闭成功后，服务端在 PostgreSQL 提交后删除待确认状态并撤销该用户全部 Refresh Session；
H5 同时清理当前会话 Cookie，Android 清理 KeyStore 会话。普通 API 每次都先验证 Redis Refresh Session，
所以其他设备即使仍持有未过期 AT，也会在下一次请求立即被拒绝。

## 会话

普通用户不再提供 `POST /api/auth/session/refresh`。每个受保护请求按以下顺序完成认证：

1. H5 从 `access_token`、`refresh_token` Cookie 读取 AT/RT；Android 从 `Authorization` 和
   `X-Refresh-Token` 读取，两端禁止跨来源回退。
2. 两端都必须提交 `X-Device-Installation-Id` 与 `X-CSRF-Token`，包括 GET 和 SSE 握手。
3. 服务端先只读校验 Redis Refresh Session、用户索引、TTL、设备、CSRF 和可选 PreAuth 绑定。
4. RT 失效时立即返回 401，不再解析 AT；AT 缺失、篡改或与 RT 用户不一致时也直接返回 401。
5. AT 未过期时继续原业务请求且不延长 RT；AT 签名合法但过期时才原子续期 RT、签发新 AT，随后继续原业务请求。

同请求续签不改变业务响应体。H5 通过 `Set-Cookie` 接收新 AT；Android 通过
`X-New-Access-Token` 接收。两端都会收到 `X-Session-Renewed: true`，RT 原文与 CSRF 保持不变。

### `POST /api/auth/session/bootstrap`

- 仅用于 H5 新标签页恢复。
- 校验 HttpOnly RT、可选 AT Cookie、安装设备、Origin 和 Fetch Metadata；这是唯一免交旧 CSRF Header 的 POST 接口。
- RT 保持不变；生成新 CSRF，旧 CSRF 立即失效。
- 续三处 Redis TTL，写入新 AT、相同 RT 和新 XSRF Cookie；响应体不返回 Token。

### `POST /api/auth/session/logout`

- 校验固定 RT、安装设备和 CSRF。
- 物理删除当前 RT Key，并删除用户 RT 反向索引中的当前字段。
- 若删除的是索引最大 TTL 字段，Lua 用最多十个剩余字段的 `HPTTL` 重算索引 Key TTL。
- H5 同时清理新 `/api` 与旧 `/api/auth/session` 路径的 RT Cookie；Android 清除包含三个 Token 的本地 KeyStore 密文。
- RT 被撤销后，即使 AT 尚未过期，下一次受保护请求也会立即返回 401。

H5 业务请求由浏览器自动携带 `/api` 路径的 `access_token` 与 `refresh_token`，并为所有受保护方法携带
`X-CSRF-Token`；Android 使用 `Authorization: Bearer <AT>`、`X-Refresh-Token` 和 `X-CSRF-Token`。
AT 只包含 `sub`、`jti`、`ver`、`iat`、`exp`，不包含 `sid` 或 `passwordVersion`。

H5 的 Spring Cookie/Header 校验、会话 CSRF HMAC 校验或会话端点 Origin/Fetch Metadata 校验失败时返回受控错误，响应和日志不输出 Token。普通业务请求只发送一次，不再调用 refresh 或自动重放；`bootstrap` 只用于新标签页、页面刷新或 CSRF 丢失后的浏览器会话恢复。

## 找回密码

| Method | Path | 请求与行为 |
| --- | --- | --- |
| POST | `/api/auth/password-reset/start` | `channel=EMAIL` 或 `SMS`，邮箱与短信二选一 |
| POST | `/api/auth/password-reset/turnstile` | 每个找回流程只验证一次 Turnstile |
| POST | `/api/auth/password-reset/send` | 返回统一提示，不泄露账号是否存在 |
| POST | `/api/auth/password-reset/verify` | 验证码正确后签发固定 TTL 五分钟的 forgetToken |
| POST | `/api/auth/password-reset/complete` | Header 提交 forgetToken，body 提交 `{ password, passwordConfirmation }`；强度不合格返回 HTTP 400 + `PASSWORD_STRENGTH_INSUFFICIENT` |

密码重置成功后：

1. PostgreSQL 原子更新密码哈希、强度等级、策略版本并执行 `password_version + 1`。
2. 数据库提交后物理删除该用户全部固定 RT 及用户 RT 索引；最多同步重试三次。
3. 撤销仍失败时返回 `SESSION_REVOCATION_FAILED`，明确密码已经修改但会话清理暂不可用。
4. 异步发送密码变更安全提醒邮件；邮件失败不回滚密码。
5. 不自动登录，前端返回登录页。

```json
{
  "passwordReset": true,
  "nextAction": "LOGIN"
}
```

## Turnstile 客户端

| Method | Path | 行为 |
| --- | --- | --- |
| GET | `/api/auth/turnstile/config` | 只公开 Site Key，不公开 Secret |
| GET | `/api/auth/turnstile/page` | Android 第一方受控 WebView 页面；action 只允许 register、login、password_reset |
| GET | `/api/auth/turnstile/page.css` | Turnstile WebView 的无状态样式资源；`no-store` |
| GET | `/api/auth/turnstile/page.js` | Turnstile WebView 的无状态客户端状态机；`no-store` |
| GET | `/api/admin/auth/hcaptcha/page` | Android 管理员第一方受控 hCaptcha WebView 页面 |
| GET | `/api/admin/auth/hcaptcha/page.css` | 管理员 hCaptcha WebView 的无状态样式资源；`private, no-store` |
| GET | `/api/admin/auth/hcaptcha/page.js` | 管理员 hCaptcha WebView 的无状态客户端状态机；`private, no-store` |

H5 注册提交一次性 Token 前先调用注册状态接口核对当前 challenge。新标签页创建注册流程后会通知同源旧标签页停止提交旧 challenge；服务端未返回 `humanVerified=true` 时，前端必须重置绿色组件并生成新 Token。

Turnstile Siteverify 的失败按可信结论分层：Cloudflare 明确拒绝 Token，或 hostname、action、cData、有效时间窗绑定失败时，普通用户认证接口返回 HTTP 403 和 `TURNSTILE_REJECTED`；连接、TLS、读取超时、非 2xx、空响应、畸形响应、供应商配置错误或无法识别的供应商错误码表示服务端未取得可信验证结论，返回 HTTP 503 和 `HUMAN_VERIFICATION_UNAVAILABLE`。503 响应固定使用“人机验证服务暂时不可用，请稍后重试。”，携带 `Cache-Control: private, no-store` 且不携带 `Retry-After`。

上述两类失败都不会把当前 Flow 标记为 `humanVerified`，供应商不可用也不会清理注册、验证码登录或找回密码 Flow Cookie。由于请求中断时无法确认一次性 Token 是否已被供应商消费，客户端不得自动重复提交旧 Token；注册、登录和找回密码页面必须重置 Turnstile 组件并生成新 Token 后再由用户重试。响应和日志禁止包含 Token、Secret、完整客户端 IP、供应商正文或底层异常消息；服务端只保留 traceId、受控诊断分类、CF-Ray 和异常类型等脱敏信息。

Android Turnstile 与管理员 hCaptcha 页面分别维护为 `turnstile-page.html/.css/.js` 和 `admin-hcaptcha-page.html/.css/.js` 三个独立 classpath 资源。两个 Controller 只执行参数、HTTP 安全边界与独立资源传输，禁止包含或拼接 HTML、CSS、JavaScript。受控 HTML 页面没有放在 Spring 自动公开的 `static` 目录，客户端必须经过对应 Controller 路由进入；公开的 CSS/JavaScript 子资源不包含 Site Key、challenge、Token、Secret 或会话数据。

Turnstile 页面从当前 Query 读取已由 Controller 白名单校验的 challenge 和 action，并调用现有 `/api/auth/turnstile/config` 获取公开 Site Key。管理员应用把既有 login/register flow 返回的公开 Site Key 与 challenge 放入受控 WebView 的临时 URL Fragment；Fragment 不进入 HTTP 请求、反向代理或服务器访问日志，页面在使用前仍执行字符集和长度校验。最终 token 继续由后端 Siteverify 校验，静态资源拆分不改变认证信任边界。

普通用户 Turnstile 与管理员 hCaptcha 的客户端采用供应商显式渲染模式。客户端必须等到供应商 `onload` ready 回调确认 `render` API 可用后再创建 widget；DOM `script.onload` 和轮询到全局对象都不能作为 SDK 已完成初始化的依据。每个验证会话只保留一个有效 widget，并使用渲染代次忽略旧异步回调。可重试错误只自动恢复一次，仍失败时停在手动“重新验证”状态；手动重试会恢复一次自动重试额度，但不会重新创建注册或登录流程。

管理员 H5 使用按用途隔离的双提交 CSRF Cookie，并统一复制到 `X-Admin-CSRF-Token` 请求头。请求路径决定后端比较哪个 Cookie；请求头名称不需要包含 register 或 login：

| 场景 | JavaScript 读取的 Cookie | 请求头 |
| --- | --- | --- |
| 普通用户会话写请求 | `XSRF-TOKEN` | `X-CSRF-Token` |
| 管理员首次注册 Flow | `admin_register_csrf` | `X-Admin-CSRF-Token` |
| 管理员登录 Flow | `admin_login_csrf` | `X-Admin-CSRF-Token` |
| 管理员登录后会话写请求 | `ADMIN-XSRF-TOKEN` | `X-Admin-CSRF-Token` |

一条请求只使用对应的一组。普通用户 `XSRF-TOKEN` 只能由普通站点同源入口签发，
管理员 `ADMIN-XSRF-TOKEN` 只能由管理员同源入口签发。公开的 `state`、`phone-country`
和 `hcaptcha/config` 会主动解析管理员 CSRF，使 H5 刷新后写入或保持
`ADMIN-XSRF-TOKEN`；该 Cookie 只承担管理员双提交 CSRF，不代表已经建立
`admin_session`。`register/start`、`login/start` 和 `session/bootstrap` 不要求已有管理员
CSRF Cookie，但成功响应必须生成下一阶段可读的 Cookie。后端只接受经 HMAC 验签的 Worker
外部 Host，不信任 `Forwarded` 或 `X-Forwarded-Host`。浏览器策略阻止新 Cookie 后，前端使用
本地错误 `ADMIN_CSRF_COOKIE_UNAVAILABLE` 并停止网络提交。

管理员注册 `/register/hcaptcha` 和登录 `/login/complete` 必须先通过用途 Cookie 与 `X-Admin-CSRF-Token` 的常量时间比较，随后才能调用 hCaptcha Siteverify。CSRF 失败不是供应商验证失败，不能触发携带同一个一次性 hCaptcha token 的自动重试。

人机验证存在三个彼此独立的超时边界：

- 客户端 SDK ready 超时为 15 秒，只约束供应商脚本从插入到明确 ready；失败时删除失效脚本并允许重新下载。
- 前端 API 请求超时约束浏览器或应用调用本项目认证接口，不代表供应商 SDK ready，也不能代替 Siteverify 超时。
- 一次性 token 产生并提交后，后端调用供应商 Siteverify 的连接与响应超时保持 8 秒；客户端尚未取得 token 时不会进入该阶段。

页面可以显示经过白名单或格式校验的供应商错误码用于排障。错误码不是凭据；日志、Storage、监控标签和新增 URL 位置禁止记录 token、Site Key、完整 challenge、邮箱、手机号或其他敏感身份信息。Turnstile 只显示六位数字代码，否则显示 `unknown`；hCaptcha 只显示客户端错误白名单中的稳定代码，否则显示 `unknown`。
