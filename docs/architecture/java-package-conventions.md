# Java 包结构规范

## 1. 模块依赖

```text
ai-temperate-web
  -> ai-temperate-service
    -> ai-temperate-mapper
      -> ai-temperate-model
        -> ai-temperate-common
```

- `web` 只处理 HTTP、拦截器、安全过滤器、Cookie、CORS、CSRF 与 OpenAPI。
- `service` 放业务编排、策略、Redis Store 接口与实现。
- `mapper` 放 MyBatis Mapper 接口与 XML。
- `model` 放领域对象、实体和枚举。
- `common` 放通用编码、HMAC、Redis Key 工厂和基础设施工具，不放认证流程。

## 2. Service 结构

每个业务 Service 必须使用接口加 `Impl`：

```text
service/user/UserLoginService.java
service/user/impl/UserLoginServiceImpl.java
```

- `@Service` 只标注实现类。
- Controller 和其他 Service 只依赖接口。
- 使用构造器注入与 `final` 字段，禁止字段注入。
- 事务边界放在实现类公开业务方法上。
- 单例 Service 不保存请求级可变状态。

## 3. 多策略结构

登录策略通过 Spring 一次注入：

```text
Map<String, LoginStrategy>
-> LoginStrategyRegistry
-> 不可变 EnumMap<LoginStrategyType, LoginStrategy>
```

Registry 在启动时拒绝重复类型，不支持类型返回受控业务错误。业务流程禁止通过连续 `if/switch`、手工 `new` 或 `ApplicationContext.getBean()` 选择策略。

## 4. 认证包映射

| 能力 | 包 | 主要类型 |
| --- | --- | --- |
| 登录业务 | `service.auth.login.service` | `LoginService` |
| 登录实现 | `service.auth.login.service.impl` | `LoginServiceImpl` |
| 登录策略 | `service.auth.login.strategy` | `LoginStrategy`、`LoginStrategyRegistry`、`LoginStrategyType` |
| 会话认证 | `service.auth.session.authentication.service` | `SessionAuthenticationService` |
| 会话认证实现 | `service.auth.session.authentication.service.impl` | `SessionAuthenticationServiceImpl` |
| 会话命令 | `service.auth.session.authentication.dto.command` | `SessionAuthenticationCommand`、`SessionBootstrapCommand`、`LogoutCommand` |
| 会话结果 | `service.auth.session.authentication.dto.result` | `SessionAuthenticationResult` |
| 固定 RT Store | `service.auth.session.refresh.store` | `RefreshSessionStore` |
| Redis RT Store | `service.auth.session.refresh.store.impl` | `RedisRefreshSessionStore` |
| 固定 RT 命令 | `service.auth.session.refresh.dto.command` | `NewRefreshSession` |
| 固定 RT 结果 | `service.auth.session.refresh.dto.result` | `RefreshSessionSnapshot`、`RefreshSessionValidation`、`RefreshSessionRevocation` |
| AT 与随机 Token | `service.auth.session.token.service` | `AuthTokenService` |
| AT 与随机 Token 实现 | `service.auth.session.token.service.impl` | `AuthTokenServiceImpl` |
| 找回密码 | `service.auth.passwordreset.service` | `PasswordResetService` |
| 找回密码实现 | `service.auth.passwordreset.service.impl` | `PasswordResetServiceImpl` |
| 注册生命周期 | `service.registration.service.lifecycle` | `RegistrationService` |
| 注册实现 | `service.registration.service.lifecycle.impl` | `RegistrationServiceImpl` |

已删除的 v2 会话类型不得重新引入：

```text
SessionCsrfRotationCommand
SessionCsrfRotationResult
RefreshSessionRotationCommand
RefreshSessionRotation
family / active / used Store API
```

## 5. Mapper 与 XML

调用链严格保持：

```text
Controller -> Service -> ServiceImpl -> Mapper -> Mapper XML -> PostgreSQL
```

- Controller 禁止直接调用 Mapper。
- Mapper XML 资源路径与 Mapper 包保持一致。
- SQL 参数化，禁止拼接用户输入。
- 集合查询使用批量 SQL，禁止循环逐条访问数据库。
- 项目禁止物理外键；关联字段必须有普通索引和显式完整性处理。

## 6. Controller 与公开 ID

- 每个 `@RestController` 必须有中文 `@Tag`，每个公开接口应该有中文 `@Operation`。
- 详细说明职责、平台、安全边界及不负责内容。
- 业务资源 PathVariable 使用固定 11 字符 Base64URL，禁止直接暴露数据库 `BIGINT`。
- Java `Long` HTTP JSON 统一序列化为字符串。

## 7. Redis 会话包约束

固定 RT v3 只允许以下 Store API：

```text
create
validateAndRenew
bootstrapAndRenew
revoke
revokeAllForUser
```

所有会话 Key 由 `RedisKeyFactory` 生成，所有跨 Key 条件检查和 TTL 修改由有界 Lua 原子执行。禁止重新加入 RT 轮换、Token Family、重放墓碑或全库扫描。
