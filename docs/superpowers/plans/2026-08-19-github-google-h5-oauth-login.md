# GitHub 与 Google H5 OAuth 登录 Implementation Plan

> **执行约束：** 本计划由根任务逐项实施，不启用子代理。项目第一阶段只交付代码与测试代码，不自动运行测试、编译、打包、依赖分析、安全扫描或外部 OAuth 联调；第二阶段必须取得用户明确同意后才执行验证命令。

**Goal:** 在现有无状态 Spring Security、Cookie 会话、Redis 注册流和 UniApp H5 登录页之上，增加 GitHub 与 Google 登录；未注册的第三方身份必须先验证手机号再自动注册，已注册身份直接进入既有登录完成/TOTP 流程，同时保证 OAuth 防登录 CSRF、防回调重放、PKCE、OIDC nonce、CORS 和 Cloudflare 同源网关边界完整。

**Architecture:** 浏览器先通过受现有双提交 Cookie CSRF 保护的 `POST /api/auth/oauth2/start` 创建短时握手，再顶层导航到 Spring OAuth2 authorization endpoint。服务端以 Redis 保存一次性 `state` 事务，并用 `HttpOnly + Secure + SameSite=Lax` 的短时浏览器绑定 Cookie 防止攻击者把自己的回调链接转交给受害者。Google 使用 OIDC `sub + nonce`，GitHub 使用稳定用户 ID；二者都使用 PKCE S256。第三方回调只创建一次性 OAuth 完成流并重定向回 H5，不直接签发正式会话。H5 随后通过受 CSRF、CORS、设备与风控约束的 API 查询状态：已有账号进入现有 `LoginCompletionService`，新账号或缺手机号账号先完成短信验证，再在 PostgreSQL 本地事务中创建/更新身份，提交成功后签发会话。

**Tech Stack:** Java 21、Spring Boot、Spring Security OAuth2 Client/OIDC、MyBatis、PostgreSQL、Redis + Lua、Spring Security Cookie CSRF、UniApp H5/Vue、Cloudflare Worker、JUnit 5、Mockito、Node.js `node:test`。

---

## 1. 已锁定的产品与安全决策

### 1.1 本期范围

- 本期只实现 **H5 浏览器 GitHub/Google 登录**。
- Android 原生 OAuth 不复用 H5 Cookie 回调；后续单独设计系统浏览器 + App Link/Deep Link + public client PKCE + Android Keystore 令牌运输。
- 一个 `userloginidentity` 账号本期只对应一种注册来源：`EMAIL`、`GITHUB` 或 `GOOGLE`。
- 本期不支持同一个账号同时绑定 GitHub 和 Google；未来若需要多重绑定，应新增独立 `user_external_identity` 表，而不是继续给 `userloginidentity` 加第二组 provider/subject 字段。
- 不因第三方邮箱与现有账号邮箱相同而静默合并账号；必须返回“需要先使用原账号登录后绑定”的受控结果。

### 1.2 字段语义

| 字段 | 语义 | 本期规则 |
| --- | --- | --- |
| `registration_source` | 账号最初注册来源 | `0=EMAIL, 1=GITHUB, 2=GOOGLE` |
| `oauth_subject` | 第三方稳定主体 ID | GitHub 用户数字 ID 转字符串；Google OIDC `sub` |
| `email` | 账号联系邮箱 | OAuth 首次注册时写入第三方已验证邮箱 |
| `email_verified` | 当前邮箱是否已完成可信验证 | 普通注册完成邮箱验证码后为 `TRUE`；Google 要求 claim 为真；GitHub 要求 emails API 返回 verified 邮箱 |
| `phone` | 已验证手机号 | OAuth 流未验手机号时保持 `NULL`，短信验证成功的同一事务中写入 |
| `password_hash` | 本地密码哈希 | OAuth 新账号为 `NULL`；只有用户中心显式设置密码后才允许密码登录 |

`email_verified` 不是“这个账号能否使用密码登录”的开关。密码登录必须同时满足：账号存在、`password_hash != NULL`、密码匹配。OAuth 邮箱即使已验证，只要 `password_hash` 为空，就不能用密码登录。

### 1.3 必须实现的三层防重复/防伪造

用户提到的“CSR 防重复”应拆成三个不同机制，三者都需要：

1. **OAuth 登录 CSRF 与回调重放**
   - 256-bit 随机 `state`；
   - `state` 与当前浏览器绑定 Cookie 的 HMAC、provider、PKCE verifier、return path、过期时间绑定；
   - Redis Lua 原子领取，第二次回调必须失败；
   - Google 再验证 OIDC `nonce`；
   - GitHub 和 Google 都启用 PKCE S256；
   - provider callback URI 精确匹配，禁止开放重定向。

2. **H5 写接口 CSRF**
   - `POST /api/auth/oauth2/start`、发送短信、验证短信、完成登录、取消流程继续使用现有 `XSRF-TOKEN` Cookie + `X-CSRF-Token` Header；
   - OAuth provider 的顶层 GET 回调不要求该 Header，因为第三方网站无法带上它，回调安全由 `state + binding cookie + nonce/PKCE` 保证。

3. **短信验证码重放**
   - Redis 只保存验证码摘要/受保护值；
   - 有效期、发送冷却、账号/IP/手机号限流、最大失败次数；
   - 验证成功后 Lua 原子消费，重复提交不能再次创建账号或绑定手机号。

参考标准：[OAuth 2.0 Security Best Current Practice (RFC 9700)](https://www.rfc-editor.org/rfc/rfc9700.html)、[Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)、[Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)。

### 1.4 为什么需要 OAuth 专用 `SameSite=Lax` Cookie

现有认证流程 Cookie 全部是 `SameSite=Strict`。GitHub/Google 登录完成后，浏览器从第三方站点以顶层导航回到本项目；Strict Cookie 在该跨站回跳中不适合作为可靠的 OAuth 握手绑定凭据。因此新增两个用途分离的 Cookie：

| Cookie | SameSite | Path | TTL | 用途 |
| --- | --- | --- | --- | --- |
| `oauth_handshake` | `Lax` | `/api/auth/oauth2` | 5～10 分钟 | provider 跨站顶层回调时绑定原浏览器；仅 OAuth 握手使用 |
| `oauth_flow` | `Strict` | `/api/auth/oauth2` | 10～30 分钟 | 回到本站后查询状态、验证手机、完成登录 |

二者都必须 `HttpOnly + Secure`，不得保存 access token、refresh token、provider token、邮箱、手机号或原始 subject；Cookie 仅保存高熵随机句柄。生产环境继续优先使用 `niko000o.site` 同源 Worker，不将整套会话 Cookie 改成 `SameSite=None`。

---

## 2. 目标交互与接口合同

### 2.1 新用户流程

```text
登录页点击 Google/GitHub
  -> POST /api/auth/oauth2/start（CORS + Cookie CSRF + 风控/设备）
  -> 设置 oauth_handshake Cookie，返回固定站内 authorizationUrl
  -> window.location.assign(authorizationUrl)
  -> GET /api/auth/oauth2/authorization/{google|github}
  -> provider 授权（state + PKCE；Google 另有 nonce）
  -> GET /api/auth/oauth2/code/{google|github}
  -> 原子消费 state，提取并验证 provider 主体与已验证邮箱
  -> 不签发正式 AT/RT；创建 oauth_flow Cookie
  -> 303 /pages/auth/oauth-return
  -> GET /api/auth/oauth2/onboarding/status = PHONE_REQUIRED
  -> POST /phone/send
  -> POST /phone/verify
  -> PostgreSQL 事务：identity + profile + quota
  -> 事务提交后 Bloom/缓存处理
  -> LoginCompletionService（如需 TOTP 则进入现有 TOTP 流）
```

### 2.2 已注册用户流程

```text
provider 回调
  -> 按 (registration_source, oauth_subject) 查询账号
  -> 已有手机号：oauth_flow 状态 EXISTING_ACCOUNT_READY
  -> H5 POST /complete
  -> LoginCompletionService
  -> 登录成功或 TOTP_REQUIRED

  -> 缺手机号：状态 PHONE_REQUIRED
  -> 验证手机号并事务更新 identity
  -> LoginCompletionService
```

### 2.3 邮箱冲突

```text
provider subject 未注册
  -> provider 已验证邮箱命中现有 userloginidentity
  -> 不创建、不覆盖、不自动绑定
  -> 状态 ACCOUNT_LINK_REQUIRED
  -> 清除 OAuth 流并提示先用原方式登录，再从用户安全中心绑定
```

### 2.4 API 清单

| 方法 | 路径 | 保护与行为 |
| --- | --- | --- |
| `POST` | `/api/auth/oauth2/start` | H5 Cookie CSRF、CORS、设备/风控；body 只允许 `GITHUB/GOOGLE`；返回固定 authorization URL |
| `GET` | `/api/auth/oauth2/authorization/github` | 顶层导航；需要有效 handshake Cookie；生成 state/PKCE |
| `GET` | `/api/auth/oauth2/authorization/google` | 顶层导航；需要有效 handshake Cookie；生成 state/PKCE/nonce |
| `GET` | `/api/auth/oauth2/code/github` | GitHub 精确 callback；原子消费 state |
| `GET` | `/api/auth/oauth2/code/google` | Google 精确 callback；原子消费 state 与验证 nonce |
| `GET` | `/api/auth/oauth2/onboarding/status` | 读取 HttpOnly oauth_flow，返回 provider、脱敏邮箱和下一步，不返回 subject/token |
| `POST` | `/api/auth/oauth2/onboarding/phone/send` | Cookie CSRF、限流；规范化 E.164 后发短信/WhatsApp（依现有策略） |
| `POST` | `/api/auth/oauth2/onboarding/phone/verify` | Cookie CSRF；原子消费验证码并创建/更新账号 |
| `POST` | `/api/auth/oauth2/onboarding/complete` | 已有且手机号齐全的账号完成登录；一次性领取 flow |
| `POST` | `/api/auth/oauth2/onboarding/cancel` | 清理 flow 与 OAuth Cookie；幂等返回 204 |

所有响应统一 `Cache-Control: no-store`。callback 额外设置 `Referrer-Policy: no-referrer`；失败只能重定向到固定 `/pages/auth/oauth-return`，以短错误码表达状态，禁止把 provider 错误详情、邮箱、code、state、token 放入 URL。

---

## 3. CORS 与边缘网关结论

### 3.1 CORS 是否必须考虑

必须考虑，但不是每一步都靠 CORS：

- `window.location.assign()` 前往 authorization endpoint，以及 provider 回跳 callback，都是浏览器**顶层导航**，不属于 `fetch/XHR`，不依赖 CORS 响应头。
- 登录页的 `POST /start`、状态查询、短信发送/验证、完成登录属于 `uni.request/fetch`；当前端与后端 origin 不同（例如本地 `https://localhost:3000 -> https://localhost:6655`）时，必须通过 CORS 与预检。
- 现有 `CorsConfigurationSource` 已覆盖 `/api/**`、允许 credentials、显式 origin 白名单和 `X-CSRF-Token`。本期复用它，不新增可由客户端自由控制的 OAuth 自定义 Header。
- `Access-Control-Allow-Origin` 必须回显精确白名单 origin，禁止 `*`；credentials 保持 `true`；预检在 Spring Security 之前处理。
- 生产 `AUTH_API_BASE_URL=''` 通过 Cloudflare Worker 同源代理时不产生浏览器 CORS，但后端仍保留显式 allowlist 以支持本地/受控环境。

### 3.2 Cloudflare Worker 必须处理的特殊点

当前 Worker 对根域 API 使用精确路由白名单，并默认拒绝上游跨主机重定向。OAuth authorization endpoint 必须 302 到 GitHub/Google，因此计划增加 OAuth 专用导航规则：

- 只允许上表列出的精确 OAuth 路径和方法；
- authorization 路由只允许重定向到：
  - `https://github.com/login/oauth/authorize`
  - Google 配置/metadata 解析出的固定官方 authorization endpoint；
- callback 只允许重定向到本站固定 `/pages/auth/oauth-return`；
- callback 允许 `Sec-Fetch-Site: cross-site`，但必须是 `GET + navigate + document`；
- 普通 API 仍禁止任意跨主机 redirect；
- OAuth 302/303 必须原样保留 `Location` 和 `Set-Cookie`，并强制 `no-store`；
- 新增 H5 页面 `/pages/auth/oauth-return` 到页面白名单。

---

## 4. 分步实施计划

### Task 1: 固化数据库约束、枚举与 MyBatis 映射

**Files:**
- Modify: `sql/001_create_users.sql`
- Create: `ai-temperate-model/src/main/java/com/example/temperate/model/auth/enums/RegistrationSource.java`
- Modify: `ai-temperate-model/src/main/java/com/example/temperate/model/user/entity/UserLoginIdentity.java`
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/identity/UserLoginIdentityMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/identity/UserLoginIdentityMapper.xml`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/profile/PersistenceSqlContractTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/identity/AuthenticationMapperContractTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/identity/MyBatisMapperXmlIntegrationTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/identity/PostgreSqlMapperIntegrationTest.java`

- [ ] **Step 1: 先补充持久化合同测试代码（第一阶段不运行）**

测试必须覆盖：

```text
password_hash 允许 NULL
registration_source 只允许 0/1/2
EMAIL 必须 oauth_subject IS NULL
GITHUB/GOOGLE 必须 oauth_subject IS NOT NULL
(registration_source, oauth_subject) 在 subject 非空时唯一
普通邮箱仍 LOWER(email) 唯一
手机号仍采用 phone IS NOT NULL 的部分唯一索引
Mapper resultMap、Base_Column_List、insert 均包含三个新增字段
按 provider + subject 查询 AuthenticationContext
password_hash 为 NULL 时映射不报错
```

- [ ] **Step 2: 完成 CREATE TABLE 约束与索引**

在已写入的四个字段基础上补齐：

```sql
CONSTRAINT chk_userloginidentity_registration_source
    CHECK (registration_source IN (0, 1, 2)),
CONSTRAINT chk_userloginidentity_oauth_identity_shape
    CHECK (
        (registration_source = 0 AND oauth_subject IS NULL)
        OR
        (registration_source IN (1, 2) AND oauth_subject IS NOT NULL)
    )
```

并增加：

```sql
CREATE UNIQUE INDEX uk_userloginidentity_oauth_provider_subject
    ON userloginidentity (registration_source, oauth_subject)
    WHERE oauth_subject IS NOT NULL;
```

索引对应真实登录查询；不为 `registration_source`、`email_verified`、`password_hash` 单独建低价值索引。

- [ ] **Step 3: 增加稳定枚举并映射实体**

```java
public enum RegistrationSource {
    EMAIL((short) 0),
    GITHUB((short) 1),
    GOOGLE((short) 2);

    private final short databaseCode;
}
```

`UserLoginIdentity` 增加 `registrationSource`、`oauthSubject`、`emailVerified`；所有顶级类型和非直观映射写中文 JavaDoc/注释。

- [ ] **Step 4: 扩展 Mapper**

新增服务端固定枚举参数查询，不接收任意列名：

```java
Optional<AuthenticationContext> findAuthenticationByOAuthSubject(
        @Param("registrationSource") short registrationSource,
        @Param("oauthSubject") String oauthSubject);
```

SQL 使用等值条件：

```sql
WHERE uli.registration_source = #{registrationSource,jdbcType=SMALLINT}
  AND uli.oauth_subject = #{oauthSubject,jdbcType=VARCHAR}
```

- [ ] **Step 5: 修正普通注册插入语义**

修改 `RegistrationServiceImpl` 创建 identity 的代码，使普通注册显式写入：

```text
registrationSource = EMAIL
oauthSubject = null
emailVerified = true
passwordHash = PasswordEncoder.encode(...)
```

不得只依赖数据库默认值掩盖业务语义。

---

### Task 2: 引入 OAuth2 Client 并建立严格配置合同

**Files:**
- Modify: `ai-temperate-web/pom.xml`
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Modify: `ai-temperate-web/src/test/resources/application-test.yml`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/properties/AuthSecurityProperties.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/config/OAuthConfigurationContractTest.java`

- [ ] **Step 1: 写配置合同测试代码（第一阶段不运行）**

断言：

```text
只允许 registrationId=github/google
client secret 没有默认值且只能由环境变量注入
GitHub scopes=read:user,user:email
Google scopes=openid,profile,email
authorization baseUri=/api/auth/oauth2/authorization
callback baseUri=/api/auth/oauth2/code/*
生产 redirectUri 使用 https://niko000o.site/api/auth/oauth2/code/{registrationId}
handshake Cookie 必须 Secure/HttpOnly/Lax/Path=/api/auth/oauth2
flow Cookie 必须 Secure/HttpOnly/Strict/Path=/api/auth/oauth2
return path 只能是固定站内路径
```

- [ ] **Step 2: 添加直接依赖**

`ai-temperate-web/pom.xml` 添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

版本继续由父 POM/Spring Boot dependency management 统一管理。

- [ ] **Step 3: 增加 provider 配置**

`application.yml` 每一个父节点、叶子和列表项前都写紧邻中文注释，Secret 不设默认值：

```yaml
# GitHub OAuth 客户端注册配置。
github:
  # GitHub OAuth App 的客户端标识，必须由运行环境注入。
  client-id: ${GITHUB_OAUTH_CLIENT_ID}
  # GitHub OAuth App 的客户端密钥，禁止在仓库中提供默认值。
  client-secret: ${GITHUB_OAUTH_CLIENT_SECRET}
```

Google 同理。redirect URI、前端完成页、握手 TTL、流程 TTL、Cookie 名称均通过强类型配置校验。

- [ ] **Step 4: 分离开发与生产 OAuth App**

运维文档明确：

```text
生产 GitHub/Google App -> https://niko000o.site/api/auth/oauth2/code/{registrationId}
开发 GitHub/Google App -> https://localhost:6655/api/auth/oauth2/code/{registrationId}
```

不得在一个开放 callback 配置中接受任意 host、端口或 return URL。

---

### Task 3: 建立 provider 策略注册表与可信主体规范化

**Files:**
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/OAuthProviderProfileStrategy.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/OAuthProviderProfileRegistry.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/OAuthProviderLoadRequest.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/OAuthProviderProfile.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/impl/GitHubOAuthProviderProfileStrategy.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/impl/GoogleOAuthProviderProfileStrategy.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/RegistryOAuth2UserService.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/provider/RegistryOidcUserService.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/provider/OAuthProviderProfileRegistryTest.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/provider/GitHubOAuthProviderProfileStrategyTest.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/provider/GoogleOAuthProviderProfileStrategyTest.java`

- [ ] **Step 1: 先写策略选择与边界测试代码（第一阶段不运行）**

覆盖：所有 provider 已注册、重复枚举启动失败、未知 provider 受控失败、每种枚举选择正确实现。Registry 构造器接收 Spring 注入的 `Map<String, OAuthProviderProfileStrategy>` 并转换为不可变 `EnumMap`，禁止 Controller switch/if、手工 new 或 `ApplicationContext.getBean()`。

- [ ] **Step 2: 定义统一输出**

```java
public record OAuthProviderProfile(
        RegistrationSource source,
        String subject,
        String verifiedEmail,
        String displayName,
        String avatarUrl) {
}
```

`subject` 与 email 先执行长度、空白、控制字符和 provider-specific 格式校验；日志中不得输出完整值。

- [ ] **Step 3: Google OIDC 规则**

- 只接受配置中的 Google issuer/audience；
- 用 OIDC `sub` 作为 `oauth_subject`，禁止用 email 作主键；
- 必须有 `email` 且 `email_verified=true`；
- `nonce` 由 OIDC 客户端验证且与 authorization request 绑定；
- 可读取 name/picture 用于首次创建 profile，但不把头像二进制同步进本地。

参考：[Google OpenID Connect](https://developers.google.com/identity/openid-connect/reference)。

- [ ] **Step 4: GitHub 规则**

- 用 `/user` 的稳定数字 `id` 转十进制字符串作为 subject；
- 若 `/user.email` 为空或不可信，使用短生命周期 provider access token 调 `/user/emails`；
- 选择 `primary=true && verified=true` 的邮箱；没有则终止，不创建缺邮箱账号；
- provider access token 只在当前请求内存中使用，不写数据库、Redis、Cookie、URL 或日志。

参考：[GitHub REST emails API](https://docs.github.com/en/rest/users/emails)、[GitHub OAuth scopes](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps)。

---

### Task 4: 实现 OAuth `state`、PKCE、nonce 与浏览器绑定的一次性握手

**Files:**
- Modify: `ai-temperate-common/src/main/java/com/example/temperate/common/redis/key/RedisKeyFactory.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/handshake/OAuthHandshakeStore.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/handshake/ProtectedOAuthHandshake.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/handshake/impl/RedisOAuthHandshakeStore.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/handshake/OAuthHandshakeCookieWriter.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/handshake/RedisBackedOAuth2AuthorizationRequestRepository.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/handshake/OAuthAuthorizationRequestResolver.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/oauth/handshake/RedisOAuthHandshakeStoreTest.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/handshake/OAuthAuthorizationRequestRepositoryTest.java`

- [ ] **Step 1: 先写攻击回归测试代码（第一阶段不运行）**

至少覆盖：

```text
state 缺失/错误 -> 拒绝
state 正确但浏览器 binding Cookie 缺失/错误 -> 拒绝
provider 与 start 时不一致 -> 拒绝
returnTo 非白名单 -> 拒绝
过期 state -> 拒绝
同一 state 首次领取成功，第二次领取失败
并发两个 callback 只有一个成功
PKCE method 必须 S256，禁止 plain
Google authorization request 必须包含 nonce
oauth_handshake Cookie 必须 Lax，不得错误改成 Strict/None
Redis/日志/Cookie 不出现 provider access token、authorization code 或原始 PKCE verifier
```

- [ ] **Step 2: 为 RedisKeyFactory 增加 OAuth Key**

示例命名：

```text
ait:<env>:auth:oauth:v1:start:<HMAC>
ait:<env>:auth:oauth:v1:state:<HMAC>
ait:<env>:auth:oauth:v1:flow:<HMAC>
ait:<env>:auth:oauth:v1:phone-code:<HMAC>
ait:<env>:limit:oauth-sms:v1:<HMAC>
```

原始 state、binding、邮箱、手机号不得直接进入 Key；统一规范化后 HMAC-SHA256 Base64URL。

- [ ] **Step 3: `POST /start` 建立浏览器握手**

生成高熵随机 binding，Cookie 只保存 binding 句柄；Redis 保存其 HMAC、provider、设备安装 ID 的 HMAC、固定 return path、风控上下文摘要和绝对过期时间。返回值只能是二选一固定 URL：

```json
{"authorizationUrl":"/api/auth/oauth2/authorization/google"}
```

- [ ] **Step 4: 自定义 authorization request**

通过 Spring Security resolver 对两家都调用 PKCE S256 customizer；Google 增加 nonce。authorization request repository 把 Spring request 转换为受保护 DTO：PKCE verifier 等临时敏感数据先用现有 secret protector 加密/认证后写 Redis。

- [ ] **Step 5: callback 原子领取**

Redis Lua 一次完成：读取、校验当前状态、比较 binding HMAC/provider、标记或删除 state、返回握手数据。任何校验失败都返回统一错误并清 Cookie，不区分“state 存在但 binding 错误”等内部原因。

---

### Task 5: 实现 OAuth 完成流与强制手机号验证

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/flow/OAuthLoginFlowStore.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/flow/OAuthLoginFlowSnapshot.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/flow/OAuthLoginFlowStatus.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/flow/impl/RedisOAuthLoginFlowStore.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/OAuthOnboardingService.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/impl/OAuthOnboardingServiceImpl.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/dto/VerificationPurpose.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/rabbit/VerificationDeliveryFlowKind.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/flow/OAuthFlowCookieWriter.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/oauth/flow/RedisOAuthLoginFlowStoreTest.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/oauth/service/OAuthOnboardingServiceImplTest.java`

- [ ] **Step 1: 写状态机与验证码重放测试代码（第一阶段不运行）**

状态只允许：

```text
PROVIDER_VERIFIED
ACCOUNT_LINK_REQUIRED
EXISTING_ACCOUNT_READY
PHONE_REQUIRED
PHONE_CODE_SENT
COMPLETION_CLAIMED
COMPLETED
```

测试非法跳转、过期、Cookie/设备不匹配、短信冷却、最大错误次数、同一验证码只能成功一次、同一 flow 只能完成一次。

- [ ] **Step 2: flow 只保存最少数据**

可以保存 provider、受保护 subject、规范化已验证邮箱、首次 profile 候选、目标 identity id、状态和时间戳；禁止保存 provider token、authorization code、ID token 或明文验证码。

- [ ] **Step 3: 复用验证码交付基础设施**

增加明确的 `OAUTH_PHONE_BINDING` purpose/flow kind，复用现有安全随机码、供应商 registry、RabbitMQ 可靠投递、限流与可观测性；不要伪造成普通注册 flow，也不要复制一套供应商 SDK。

- [ ] **Step 4: 服务层强制前置条件**

不使用通用 MVC interceptor 作为“必须填手机”的核心保障。每个 service 方法都必须校验受保护 flow、状态、设备绑定和过期时间；这样 Controller、调度器或未来内部调用也不能绕过。Spring Security 只负责 HTTP 层 CSRF/CORS，业务状态机负责手机号前置条件。

---

### Task 6: 实现账号解析、自动注册与手机号补全事务

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/OAuthAccountResolutionService.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/impl/OAuthAccountResolutionServiceImpl.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/OAuthAccountProvisioningService.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/service/impl/OAuthAccountProvisioningServiceImpl.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/dto/OAuthAccountResolution.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/oauth/service/OAuthAccountResolutionServiceImplTest.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/oauth/service/OAuthAccountProvisioningServiceImplTest.java`

- [ ] **Step 1: 写账号决策矩阵测试代码（第一阶段不运行）**

| provider subject | email | phone | 结果 |
| --- | --- | --- | --- |
| 已存在 | 任意 | 已有 | `EXISTING_ACCOUNT_READY` |
| 已存在 | 任意 | `NULL` | `PHONE_REQUIRED` |
| 不存在 | 未占用 | 无 | `PHONE_REQUIRED`，验证后新建 |
| 不存在 | 已命中其他账号 | 任意 | `ACCOUNT_LINK_REQUIRED` |
| 不存在 | 未占用 | 命中其他账号手机号 | 不合并，受控冲突 |

- [ ] **Step 2: provider subject 是登录主键**

先按 `(registration_source, oauth_subject)` 查找；只有未命中时才用 verified email 做冲突检测。不得反过来仅凭 email 登录第三方账号。

- [ ] **Step 3: 新账号本地事务**

`OAuthAccountProvisioningServiceImpl.provisionNewAccount(...)` 标注 `@Transactional`，顺序固定：

```text
重新检查 provider subject/email/phone 冲突
-> insert userloginidentity
-> insert user_profile
-> insert user_membership_quota
-> 校验每条影响行数
-> 返回 AuthenticationContext
```

identity 值：

```text
registrationSource = GITHUB/GOOGLE
oauthSubject = verified provider subject
email = verified provider email
emailVerified = true
phone = verified E.164 phone
passwordHash = null
passwordVersion = 项目初始版本
```

唯一约束冲突要转换为幂等/账号冲突结果，不把 SQL 详情暴露给客户端。

- [ ] **Step 4: 旧 OAuth 账号补手机号事务**

独立公开事务方法重新确认当前 `phone IS NULL`，写入已验证手机号并校验影响行数。若并发请求已经写入相同手机号，返回幂等成功；写入不同手机号则拒绝并要求重新登录。

- [ ] **Step 5: 提交后副作用**

只在 PostgreSQL 提交成功后：更新 identity Bloom、删除相关 ID/email/phone 缓存、清理 OAuth flow。缓存删除失败有限重试并由 TTL 兜底，不宣称强一致。

- [ ] **Step 6: 不在事务内部签发会话**

事务 Bean 返回认证上下文后，由另一个 Bean 调 `LoginCompletionService.complete(...)`；避免事务回滚但 AT/RT 已发出的不一致，也避免同类 `this.method()` 绕过 Spring AOP。

---

### Task 7: 接入 Spring Security OAuth2 Login 与 Web API

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/SecurityConfiguration.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/controller/OAuthLoginController.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/controller/OAuthOnboardingController.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/handler/OAuthAuthenticationSuccessHandler.java`
- Create: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/oauth/handler/OAuthAuthenticationFailureHandler.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/api/AuthExceptionHandler.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/AuthWebMvcConfiguration.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/controller/OAuthLoginControllerTest.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/controller/OAuthOnboardingControllerTest.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/oauth/handler/OAuthCallbackSecurityTest.java`

- [ ] **Step 1: 写 Controller/Security 合同测试代码（第一阶段不运行）**

断言：

```text
所有公开 Controller 有中文 @Tag 和 @Operation
POST start/send/verify/complete/cancel 缺 CSRF 时 403
authorization/callback GET 不要求 X-CSRF-Token，但必须通过 state/binding 校验
callback 不直接写 access_token/refresh_token
callback 只 303 到固定 oauth-return 页面
status 不返回 subject/provider token/完整邮箱
重复 complete 失败
ACCOUNT_LINK_REQUIRED 不创建账号
已有 TOTP 账号复用既有 TOTP_REQUIRED 结果与 Cookie
```

- [ ] **Step 2: 在 H5 SecurityFilterChain 配置 oauth2Login**

配置自定义：

```text
authorizationEndpoint.baseUri=/api/auth/oauth2/authorization
authorizationRequestResolver=PKCE/nonce resolver
authorizationRequestRepository=Redis-backed repository
redirectionEndpoint.baseUri=/api/auth/oauth2/code/*
userInfoEndpoint.oauth2UserService=registry OAuth2 service
userInfoEndpoint.oidcUserService=registry OIDC service
successHandler=fixed redirect + oauth flow
failureHandler=fixed redirect + safe error
```

保持 `SessionCreationPolicy.STATELESS`，禁止回退到默认 `HttpSessionOAuth2AuthorizationRequestRepository`。

- [ ] **Step 3: start 接口先做风控与设备绑定**

`POST /start` 沿用现有 H5 `Origin`、设备安装 ID、pre-auth/风险上下文校验。provider 只能通过枚举白名单解析，客户端字符串不得作为 Spring Bean 名称。

- [ ] **Step 4: callback 只创建 flow**

success handler 规范化 provider profile、解析账号状态、创建短时 `oauth_flow`，清 handshake Cookie 后 303；正式会话由后续 same-site POST 签发。

- [ ] **Step 5: 完成接口复用现有会话/TOTP**

`/complete` 或 phone verify 成功后调用 `LoginCompletionService` 和既有 `AuthCookieWriter/AuthFlowCookieWriter`：

```text
SUCCESS -> 写 access/refresh/CSRF Cookie
TOTP_REQUIRED -> 写现有 TOTP challenge Cookie，前端跳现有 totp-login
```

---

### Task 8: 加固 nullable password 与普通登录隔离

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/login/strategy/impl/PasswordLoginStrategy.java`
- Create: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/login/strategy/impl/PasswordLoginStrategyTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/login/service/impl/LoginServiceImpl.java`

- [ ] **Step 1: 写 OAuth 账号密码登录回归测试代码（第一阶段不运行）**

`password_hash == null` 时：

- 不调用 `PasswordEncoder.matches(raw, null)`；
- 返回与密码错误相同的外部错误，避免枚举账号来源；
- 记录脱敏审计原因 `PASSWORD_NOT_CONFIGURED`，不得记录邮箱或密码；
- 邮箱验证码登录是否允许保持现有产品规则，不把 `email_verified` 当作密码存在标志。

- [ ] **Step 2: 设置密码留给用户安全中心**

本 OAuth 登录计划不自动生成密码、不要求 OAuth 注册时设置密码。后续用户中心“设置密码”必须在已登录 + 二次确认边界内调用统一 `PasswordEncoder`，成功后 `password_hash` 由 `NULL` 变为编码值。

---

### Task 9: 配置 CORS、Cookie 与 Cloudflare OAuth 导航

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/SecurityConfiguration.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/properties/AuthSecurityProperties.java`
- Modify: `cloudflare/api-gateway/src/main-site-policy.js`
- Modify: `cloudflare/api-gateway/src/index.js`
- Modify: `cloudflare/api-gateway/test/main-site-policy.test.js`
- Modify: `cloudflare/api-gateway/test/index.test.js`
- Modify: `docs/operations/frontend-public-deployment.md`

- [ ] **Step 1: 写 CORS 与边缘回归测试代码（第一阶段不运行）**

覆盖：

```text
受信前端 origin 的 OPTIONS 返回精确 Allow-Origin + credentials
未知 origin 不返回允许头
OAuth JSON POST 接受 Content-Type/X-CSRF-Token/X-Device-Installation-Id/X-Client-Platform
callback 顶层导航不要求 CORS
精确 GitHub/Google authorization redirect 被放行
其他跨主机 redirect 仍被拒绝
callback 只允许 GET + navigate + document
OAuth response no-store 且 Set-Cookie/Location 不被 Worker 删除
任意第三 provider 路径、尾斜杠、编码变体被 Worker 拒绝
```

- [ ] **Step 2: 保留显式 CORS 白名单**

本地至少配置实际 H5 origin，例如：

```text
CORS_ALLOWED_ORIGINS=https://localhost:3000
```

若存在多个受控 origin，使用项目现有列表绑定格式；不得使用 `*`。生产同源部署仍显式填写生产 origin，防止绕过 Worker 直连后端时出现宽松策略。

- [ ] **Step 3: OAuth 路由使用精确边缘白名单**

`main-site-policy.js` 增加本计划 API 与 `/pages/auth/oauth-return`；callback provider 使用 GitHub/Google 两条精确路径，不用通配任意 `{registrationId}` 暴露第三方注册。

- [ ] **Step 4: 定向允许 provider redirect**

`index.js` 只在 `route.oauthAuthorization === true` 时校验并放行官方 authorization host/path。禁止为整个 `/api/auth/**` 关闭跨主机 redirect 防护。

---

### Task 10: 增加 H5 登录按钮、OAuth 回跳页与手机号补全 UI

**Files:**
- Modify: `fornted/common/auth/auth-api.js`
- Modify: `fornted/common/auth/config.js`
- Modify: `fornted/pages/auth/login.vue`
- Create: `fornted/pages/auth/oauth-return.vue`
- Modify: `fornted/pages.json`
- Modify: `fornted/pages/auth/login.test.js`
- Create: `fornted/pages/auth/oauth-return.test.js`
- Modify: `fornted/pages/auth/auth-ui-structure.test.cjs`

- [ ] **Step 1: 写前端行为测试代码（第一阶段不运行）**

测试：

```text
Google/GitHub 按钮只在 H5 显示
点击先 publicRequest POST /start，再 window.location.assign 固定 URL
不得用 AJAX 请求 provider authorization URL
busy 时防双击
OAuth return 页面只能调用 status/send/verify/complete/cancel
刷新 return 页面可从 HttpOnly flow Cookie 恢复状态
页面不读取/显示 state、code、subject、provider token
PHONE_REQUIRED 才显示手机号输入和验证码
ACCOUNT_LINK_REQUIRED 显示原账号登录提示
TOTP_REQUIRED 跳转现有 TOTP 页面
```

- [ ] **Step 2: auth-api 增加最小方法**

```js
oauthStart(provider)
oauthStatus()
oauthPhoneSend(phone, deliveryMethod)
oauthPhoneVerify(code)
oauthComplete()
oauthCancel()
```

全部复用 `publicRequest` 的 `withCredentials`、CSRF 初始化、设备与平台 Header；不在 localStorage/sessionStorage 保存 flow token。

- [ ] **Step 3: 登录按钮导航方式**

```js
const result = await authApi.oauthStart('GOOGLE')
window.location.assign(result.authorizationUrl)
```

只接受前端本地白名单中的两个相对路径；即使后端响应被污染，也禁止导航到任意 origin。

- [ ] **Step 4: OAuth return 页面状态驱动**

页面只按服务端状态渲染：加载中、需手机号、可完成、需原账号绑定、失败。手机号复用现有 country picker/E.164 校验组件，不复制一套号码规则。

- [ ] **Step 5: 可访问性与失败恢复**

按钮有明确 provider 名称和 busy 状态；错误区域 `role=alert`；返回页支持取消回登录；网络失败不自动重复提交验证码或 complete；刷新不会导致 provider callback 重放。

---

### Task 11: 统一错误、审计、限流与隐私边界

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/enums/OAuthLoginErrorCode.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/exception/OAuthLoginException.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/observer/OAuthLoginObserver.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/oauth/observer/impl/MicrometerOAuthLoginObserver.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/api/AuthExceptionHandler.java`
- Modify: `docs/operations/network-risk-rollout.md`
- Create: `docs/operations/oauth-login-rollout.md`

- [ ] **Step 1: 定义稳定外部错误码**

至少包括：

```text
OAUTH_PROVIDER_UNSUPPORTED
OAUTH_HANDSHAKE_INVALID
OAUTH_CALLBACK_EXPIRED
OAUTH_PROVIDER_EMAIL_UNVERIFIED
OAUTH_ACCOUNT_LINK_REQUIRED
OAUTH_PHONE_REQUIRED
OAUTH_PHONE_CONFLICT
OAUTH_FLOW_EXPIRED
OAUTH_FLOW_ALREADY_USED
OAUTH_PROVIDER_UNAVAILABLE
```

callback 重定向只携带通用短状态；详细分类留在服务端受控指标，禁止向 URL 泄露 provider error_description。

- [ ] **Step 2: 限流维度**

对 start、callback failure、短信发送、短信验证、complete 分别记录受保护的 IP/device/provider/flow 摘要。不得使用完整邮箱、手机号、state、subject、Redis Key 作为标签。

- [ ] **Step 3: 监控指标**

```text
oauth.start / callback.success / callback.failure
oauth.state.replay / binding.mismatch / nonce.failure
oauth.provider.latency / provider.error
oauth.phone.send / verify / conflict
oauth.provision.success / conflict / rollback
oauth.complete / totp_required
```

- [ ] **Step 4: 运维回滚**

通过 provider enable 配置分别关闭 GitHub/Google 按钮和入口；关闭后已有 EMAIL 登录不受影响。回滚不删除新增数据库列/索引，避免破坏已创建 OAuth 账号；仅停止新 OAuth 流量。

---

### Task 12: 第一阶段静态交付检查（不执行测试/编译）

**Files:**
- Review: 本计划涉及的全部修改文件

- [ ] **Step 1: 人工检查架构依赖**

```text
Controller -> Service 接口
Service Impl -> Mapper/Store
Mapper -> Model
无下层反向依赖 web
所有 Service 均为接口 + Impl、构造器注入 final 字段
provider 多实现只经不可变 Registry 选择
```

- [ ] **Step 2: 人工检查安全不变量**

```text
无默认 HttpSession authorization request repository
无明文 OAuth Secret/provider token/code/PKCE verifier 日志或持久化
state/flow/验证码均一次性原子消费
无动态 return URL/open redirect
password_hash NULL 不触发 matches 空值
未验手机号前不签发正式会话
邮箱冲突不自动合并账号
```

- [ ] **Step 3: 人工检查项目规范**

```text
所有新增/修改 Java 顶级类型有说明职责的中文 JavaDoc
安全、边界转换、事务与 Lua 原理有紧邻中文注释
所有 YAML 配置行前有紧邻中文注释
所有 Controller 有中文 @Tag/@Operation
Java 中无 HTML/CSS/JavaScript
无物理 FOREIGN KEY/REFERENCES
```

- [ ] **Step 4: 交付时明确未运行项**

第一阶段报告必须写明：Maven 测试、Spring 上下文、PostgreSQL、Redis、Cloudflare Worker、前端测试、真实 GitHub/Google OAuth 联调均未执行，等待用户批准第二阶段。

---

### Task 13: 第二阶段验证与真实 OAuth 联调（必须先取得用户授权）

**Files:**
- Test: 各模块上述测试文件
- Verify: `docs/operations/oauth-login-rollout.md`

- [ ] **Step 1: 运行定向单元/合同测试**

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main
mvn -pl ai-temperate-mapper -Dtest=PersistenceSqlContractTest,AuthenticationMapperContractTest,MyBatisMapperXmlIntegrationTest test
mvn -pl ai-temperate-service -Dtest='*OAuth*Test,PasswordLoginStrategyTest' test
mvn -pl ai-temperate-web -Dtest='*OAuth*Test,OAuthConfigurationContractTest' test
```

- [ ] **Step 2: 运行前端与 Worker 定向测试**

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main\fornted
npm test -- --runInBand pages/auth/login.test.js pages/auth/oauth-return.test.js

cd C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npm test -- main-site-policy.test.js index.test.js
```

- [ ] **Step 3: 运行 Spring 配置与上下文测试**

使用测试专用虚假 client id/secret，不调用真实 provider；确认 YAML 可以解析绑定、SecurityFilterChain 启动、两个 provider 注册且默认 HttpSession repository 未被使用。

- [ ] **Step 4: PostgreSQL/Redis 集成验证**

验证：

```text
OAuth 唯一索引阻止并发重复注册
nullable password 正常映射
state/flow/phone code 并发消费只有一个成功
事务失败时 identity/profile/quota 全部回滚
提交后缓存/Bloom 行为符合项目接受的一致性窗口
```

- [ ] **Step 5: CORS 与浏览器合同验证**

分别验证：生产同源 Worker、本地跨 origin H5、恶意 origin、预检、credentials、Lax handshake callback、Strict flow Cookie；确认 authorization/callback 顶层导航不被误判为 CORS 请求。

- [ ] **Step 6: GitHub/Google 沙箱账号联调**

至少覆盖：

```text
Google 新账号 -> 手机验证 -> 自动注册 -> 登录
GitHub 公开邮箱为空但 emails API 有 primary verified 邮箱 -> 成功
GitHub 无 verified 邮箱 -> 受控失败
已有 provider subject -> 直接登录
已有 TOTP -> 转现有 TOTP
同邮箱不同来源 -> ACCOUNT_LINK_REQUIRED
取消授权/provider error -> 固定安全失败页
复制 callback 到另一个浏览器 -> binding mismatch
刷新 callback -> state replay 拒绝
```

- [ ] **Step 7: 索引与性能验证**

对 provider 登录查询执行：

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ...
FROM userloginidentity
WHERE registration_source = 1
  AND oauth_subject = '...';
```

确认使用 `uk_userloginidentity_oauth_provider_subject`，并记录真实执行计划；不得只凭索引存在就宣称性能达标。

- [ ] **Step 8: 最终回归与发布门禁**

在定向验证通过后，再经用户批准运行完整模块测试/打包。发布前配置生产 provider callback、Secret、CORS origin、Cookie Secure/Domain、Worker route；先单 provider 小流量开启，观察 replay/binding/provider/phone/provision 指标后再开放第二个 provider。

---

## 5. 完成标准

- GitHub/Google 均使用稳定 subject，不使用 email 作为 OAuth 主键。
- OAuth 新用户不设置密码，`password_hash=NULL`，且未验手机号前绝不签发正式会话。
- 普通注册写 `EMAIL + email_verified=true + 非空 password_hash`。
- 已注册 provider 账号复用既有会话签发、TOTP、审计和限流链路。
- `state + binding Cookie + PKCE S256 + Google nonce` 均存在且通过一次性并发测试。
- H5 写操作继续受 Cookie CSRF；本地跨域预检与 credentials 正常；生产同源 Worker 路由正常。
- provider 回调没有 access/refresh/provider token、code、state 或个人信息泄漏到 URL/日志。
- 邮箱/手机号冲突不静默合并，数据库唯一约束是最终并发裁决。
- 第一阶段所有代码与测试代码交付；第二阶段所有获批验证有真实输出和缺陷闭环后，才可声明功能完成。
