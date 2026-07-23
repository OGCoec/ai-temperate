# Java Package Layering Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将全部 Java 模块统一重构为“业务能力 → 代码角色 → `impl`”包结构，同时保持现有运行行为不变。

**Architecture:** 按模块自底向上迁移，顺序固定为 `common -> model -> mapper -> service -> web -> tests`。每一阶段只移动文件并更新 `package`、`import`、反射类名、MyBatis namespace/type 和 Spring 扫描引用，不修改方法签名、业务分支、SQL、Lua 或配置值。

**Tech Stack:** Java 21、Spring Boot、Spring MVC、MyBatis、Maven、PostgreSQL、Redis、RabbitMQ、Lombok。

---

## 迁移约束

- 项目根目录没有 Git 元数据，因此本计划不包含 commit 步骤。
- 使用 PowerShell `Move-Item -LiteralPath` 完成纯目录移动；移动前确认目标位于 `C:\Users\damn\Desktop\ai-temperate`。
- 使用 `apply_patch` 修改 Java `package`、`import`、XML namespace/type 和测试中的字符串类名。
- 禁止通过全局无条件字符串替换修改所有文件；每个模块完成后用 `rg` 检查旧包残留。
- 不创建名为 `interface` 的包。
- 不改变任何 public 方法签名、DTO 字段、枚举常量、异常编码、Bean 名称或 Spring 注解语义。
- 不运行详细测试；最后只执行一次 `mvn -DskipTests test-compile`。

### Task 1: 建立包结构契约与迁移清单

**Files:**
- Create: `docs/architecture/java-package-conventions.md`

- [ ] **Step 1: 编写包结构规范文档**

文档必须明确以下允许的叶子包：

```text
service
service.impl
dto.command
dto.query
dto.result
dto.internal
domain
entity
store
store.impl
strategy
strategy.impl
registry
observer
observer.impl
component
component.impl
config
exception
enums
controller
interceptor
```

同时写明：同一叶子包不得混放 Service 接口、实现类、DTO、配置和枚举。

- [ ] **Step 2: 记录当前到目标包的映射表**

在规范文档增加四列表格：`模块 | 当前包 | 目标包 | 文件`。后续任务严格以该表执行，防止同一个类被重复移动。

### Task 2: 重构 common 模块

**Files:**
- Move: `ai-temperate-common/src/main/java/com/example/temperate/common/id/PublicIdCodec.java` -> `.../common/codec/id/PublicIdCodec.java`
- Move: `.../common/redis/RedisKeyFactory.java` -> `.../common/redis/key/RedisKeyFactory.java`
- Move: `.../common/security/HmacIdentifier.java` -> `.../common/security/hmac/HmacIdentifier.java`
- Move: `.../common/security/HmacSha256Identifier.java` -> `.../common/security/hmac/HmacSha256Identifier.java`
- Move: `.../common/validation/EmailAddressNormalizer.java` -> `.../common/validation/email/EmailAddressNormalizer.java`
- Move: `.../common/springutils/CountingBloomFilter.java` -> `.../common/bloom/counting/CountingBloomFilter.java`
- Move: `.../common/springutils/JwtUtils.java` -> `.../common/jwt/component/JwtUtils.java`
- Move: `.../common/springutils/MailUtils.java` -> `.../common/mail/component/MailUtils.java`
- Move: `.../common/springutils/AsyncExecutorConfig.java` -> `.../common/async/config/AsyncExecutorConfiguration.java`
- Move: `.../common/springutils/SnowflakeConfig.java` -> `.../common/id/snowflake/config/SnowflakeConfiguration.java`
- Move: `.../common/springutils/SnowflakeIdWorker.java` -> `.../common/id/snowflake/component/SnowflakeIdWorker.java`
- Move: `.../common/utils/AliyunSmsClientFactory.java` -> `.../common/sms/factory/AliyunSmsClientFactory.java`
- Move: `.../common/utils/HybridIdCodec.java` -> `.../common/codec/id/HybridIdCodec.java`
- Move: `.../common/utils/HybridSemaphoreIdWorker.java` -> `.../common/id/snowflake/component/HybridSemaphoreIdWorker.java`
- Move: `.../common/CommonModule.java` -> `.../common/module/CommonModule.java`
- Modify: all imports referring to the moved common classes
- Move: corresponding common tests to matching packages

- [ ] **Step 1: 创建目标目录并逐文件移动**

目录必须与目标 package 完全一致，例如：

```java
package com.example.temperate.common.codec.id;
```

- [ ] **Step 2: 只在类名确实不符合职责时重命名配置类**

将 `AsyncExecutorConfig` 改为 `AsyncExecutorConfiguration`，将 `SnowflakeConfig` 改为 `SnowflakeConfiguration`；同步更新构造引用和测试类名。其他类不改名。

- [ ] **Step 3: 更新全部下游 import**

检查五个模块：

```powershell
rg -n "common\.(id|redis|security|springutils|utils|validation)" ai-temperate-* -g "*.java" -g "*.xml"
```

期望：仅出现新包路径，不出现 `common.springutils` 或 `common.utils`。

### Task 3: 重构 model 模块

**Files:**
- Move: `ai-temperate-model/src/main/java/com/example/temperate/model/auth/AuthenticationContext.java` -> `.../model/auth/domain/AuthenticationContext.java`
- Move: `.../model/auth/AccountStatus.java` -> `.../model/auth/enums/AccountStatus.java`
- Move: `.../model/user/UserLoginIdentity.java` -> `.../model/user/entity/UserLoginIdentity.java`
- Move: `.../model/user/UserProfile.java` -> `.../model/user/entity/UserProfile.java`
- Move: `.../model/ModelModule.java` -> `.../model/module/ModelModule.java`
- Modify: mapper XML `type`、`javaType` and all Java imports

- [ ] **Step 1: 移动 Entity、领域对象和枚举**

确保 `entity` 只包含数据库实体，`domain` 只包含业务只读上下文，`enums` 只包含枚举。

- [ ] **Step 2: 更新 MyBatis 完整类名**

`UserLoginIdentityMapper.xml` 中至少更新：

```xml
type="com.example.temperate.model.user.entity.UserLoginIdentity"
javaType="com.example.temperate.model.auth.enums.AccountStatus"
```

- [ ] **Step 3: 检查旧模型包残留**

```powershell
rg -n "model\.(auth\.(AccountStatus|AuthenticationContext)|user\.(UserLoginIdentity|UserProfile))" . -g "*.java" -g "*.xml"
```

期望：无旧完整类名。

### Task 4: 重构 mapper 模块及 XML namespace

**Files:**
- Move: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/UserLoginIdentityMapper.java` -> `.../mapper/user/identity/UserLoginIdentityMapper.java`
- Move: `.../mapper/user/UserProfileMapper.java` -> `.../mapper/user/profile/UserProfileMapper.java`
- Move: `.../mapper/MapperModule.java` -> `.../mapper/module/MapperModule.java`
- Move: `ai-temperate-mapper/src/main/resources/mapper/user/UserLoginIdentityMapper.xml` -> `.../mapper/user/identity/UserLoginIdentityMapper.xml`
- Move: `.../mapper/user/UserProfileMapper.xml` -> `.../mapper/user/profile/UserProfileMapper.xml`
- Modify: XML namespace and mapper imports
- Move: mapper tests into `mapper/user/identity` or `mapper/user/profile`

- [ ] **Step 1: 移动 Mapper 接口**

每个目标包只放同一业务对象的 Mapper 接口，不创建 `impl`，因为 MyBatis 在运行时生成实现。

- [ ] **Step 2: 同步 XML namespace**

```xml
<mapper namespace="com.example.temperate.mapper.user.identity.UserLoginIdentityMapper">
```

和：

```xml
<mapper namespace="com.example.temperate.mapper.user.profile.UserProfileMapper">
```

- [ ] **Step 3: 更新 Service 和测试 import**

使用精确旧完整类名逐项替换，禁止只替换 `mapper.user` 前缀。

### Task 5: 重构 auth/login 业务包

**Files:**
- Move: `service/auth/login/LoginService.java` -> `service/auth/login/service/LoginService.java`
- Move: `service/auth/login/impl/LoginServiceImpl.java` -> `service/auth/login/service/impl/LoginServiceImpl.java`
- Move: `LoginCommand.java` -> `dto/command/LoginCommand.java`
- Move: `LoginResult.java` -> `dto/result/LoginResult.java`
- Move: `NormalizedLoginInput.java` -> `dto/internal/NormalizedLoginInput.java`
- Move: `LoginInputNormalizer.java` -> `component/normalizer/LoginInputNormalizer.java`
- Move: `LoginException.java` -> `exception/LoginException.java`
- Move: `LoginErrorCode.java` -> `enums/LoginErrorCode.java`
- Move: `LoginIdentifierType.java` -> `enums/LoginIdentifierType.java`
- Move: `audit/LoginAuditObserver.java` -> `audit/observer/LoginAuditObserver.java`
- Move: `audit/MicrometerLoginAuditObserver.java` -> `audit/observer/impl/MicrometerLoginAuditObserver.java`
- Move: `audit/LoginAuditOutcome.java` and `LoginAuditReason.java` -> `audit/enums/`

- [ ] **Step 1: 移动登录 Service、DTO、组件、异常和枚举**

调用方只能依赖：

```java
import com.example.temperate.service.auth.login.service.LoginService;
```

禁止注入 `LoginServiceImpl`。

- [ ] **Step 2: 更新登录审计包**

观察器接口和 Micrometer 实现分包，枚举不得继续与观察器混放。

- [ ] **Step 3: 同步登录测试包和反射字符串**

更新 `AuthenticationBusinessContractTest` 中的完整类名，使其断言新包结构。

### Task 6: 重构 auth/login/limit 业务包

**Files:**
- Move: `limit/LoginRateLimitService.java` -> `limit/service/LoginRateLimitService.java`
- Move: `limit/impl/LoginRateLimitServiceImpl.java` -> `limit/service/impl/LoginRateLimitServiceImpl.java`
- Move: `limit/LoginFailureStore.java` -> `limit/store/LoginFailureStore.java`
- Move: `limit/impl/RedisLoginFailureStore.java` -> `limit/store/impl/RedisLoginFailureStore.java`
- Move: `LoginAttempt.java` and `ProtectedLoginAttempt.java` -> `limit/dto/`
- Move: `LoginLimitDecision.java` -> `limit/enums/`
- Move: `LoginRateLimitInfrastructureException.java` -> `limit/exception/`
- Move: corresponding tests to matching target packages

- [ ] **Step 1: 分开限流 Service 与 Redis Store**

`service.impl` 只能包含 `LoginRateLimitServiceImpl`；`store.impl` 只能包含 `RedisLoginFailureStore`。

- [ ] **Step 2: 更新 Lua 调用类 import，不移动 Lua 资源**

Lua 保持在：

```text
ai-temperate-service/src/main/resources/lua/auth-login/
```

避免仅为 Java 包结构改变 Redis 脚本 classpath。

- [ ] **Step 3: 更新合同测试的包名断言**

`LoginLimitThreeDimensionContractTest` 必须指向新的 DTO、Store 和 Service 包。

### Task 7: 重构 auth/session、token 与 protection

**Files:**
- Move: `auth/authentication/SessionAuthenticationService.java` -> `auth/session/authentication/service/SessionAuthenticationService.java`
- Move: `auth/authentication/impl/SessionAuthenticationServiceImpl.java` -> `auth/session/authentication/service/impl/SessionAuthenticationServiceImpl.java`
- Move: `auth/authentication/SessionAuthenticationCommand.java` -> `auth/session/authentication/dto/command/SessionAuthenticationCommand.java`
- Move: `auth/authentication/SessionCsrfRotationCommand.java` -> `auth/session/authentication/dto/command/SessionCsrfRotationCommand.java`
- Move: `auth/authentication/LogoutCommand.java` -> `auth/session/authentication/dto/command/LogoutCommand.java`
- Move: `auth/authentication/SessionAuthenticationResult.java` -> `auth/session/authentication/dto/result/SessionAuthenticationResult.java`
- Move: `auth/authentication/SessionCsrfRotationResult.java` -> `auth/session/authentication/dto/result/SessionCsrfRotationResult.java`
- Move: `SessionPrincipal.java` -> `auth/session/authentication/domain/SessionPrincipal.java`
- Move: `auth/authentication/SessionAuthenticationException.java` -> `auth/session/authentication/exception/SessionAuthenticationException.java`
- Move: `auth/authentication/SessionAuthenticationErrorCode.java` -> `auth/session/authentication/enums/SessionAuthenticationErrorCode.java`
- Move: `auth/session/RefreshSessionStore.java` -> `auth/session/refresh/store/RefreshSessionStore.java`
- Move: `auth/session/impl/RedisRefreshSessionStore.java` -> `auth/session/refresh/store/impl/RedisRefreshSessionStore.java`
- Move: `auth/session/NewRefreshSession.java` -> `auth/session/refresh/dto/command/NewRefreshSession.java`
- Move: `auth/session/RefreshSessionRotationCommand.java` -> `auth/session/refresh/dto/command/RefreshSessionRotationCommand.java`
- Move: `auth/session/RefreshSessionRotation.java` -> `auth/session/refresh/dto/result/RefreshSessionRotation.java`
- Move: `auth/session/RefreshSessionSnapshot.java` -> `auth/session/refresh/dto/result/RefreshSessionSnapshot.java`
- Move: `auth/session/RefreshSessionValidation.java` -> `auth/session/refresh/dto/result/RefreshSessionValidation.java`
- Move: `auth/token/AuthTokenService.java` -> `auth/session/token/service/AuthTokenService.java`
- Move: `auth/token/impl/AuthTokenServiceImpl.java` -> `auth/session/token/service/impl/AuthTokenServiceImpl.java`
- Move: `VerifiedAccessToken.java` -> `auth/session/token/dto/result/VerifiedAccessToken.java`
- Move: `auth/protection/AuthSessionSecretProtector.java` -> `auth/protection/component/AuthSessionSecretProtector.java`
- Move: `auth/AuthSessionInfrastructureConfiguration.java` -> `auth/config/AuthSessionInfrastructureConfiguration.java`

- [ ] **Step 1: 将 authentication 归入 session 业务能力**

迁移后不存在 `service.auth.authentication` 顶层包，认证会话统一位于 `service.auth.session.authentication`。

- [ ] **Step 2: 分离 Refresh Store、命令和结果**

`RefreshSessionStore` 与 Redis 实现分别位于 `store` 和 `store.impl`；Record 不得与 Store 接口混放。

- [ ] **Step 3: 将 Token Service 归入 session/token**

保持 Bean 和接口签名不变，仅修改包名。

- [ ] **Step 4: 同步测试、反射字符串和 Lua 合同测试**

Lua 继续保留在：

```text
ai-temperate-service/src/main/resources/lua/auth-session/
```

### Task 8: 重构 registration 生命周期、DTO 和异常

**Files:**
- Move: `registration/RegistrationService.java` -> `registration/service/lifecycle/RegistrationService.java`
- Move: `registration/impl/RegistrationServiceImpl.java` -> `registration/service/lifecycle/impl/RegistrationServiceImpl.java`
- Move: `registration/RegistrationStartCommand.java` -> `registration/dto/command/RegistrationStartCommand.java`
- Move: `registration/RegistrationCompleteCommand.java` -> `registration/dto/command/RegistrationCompleteCommand.java`
- Move: `registration/RegistrationSendCodeCommand.java` -> `registration/dto/command/RegistrationSendCodeCommand.java`
- Move: `registration/RegistrationVerifyCodeCommand.java` -> `registration/dto/command/RegistrationVerifyCodeCommand.java`
- Move: `registration/RegistrationTurnstileCommand.java` -> `registration/dto/command/RegistrationTurnstileCommand.java`
- Move: `RegistrationStatusQuery.java` -> `registration/dto/query/`
- Move: `registration/RegistrationStartResult.java` -> `registration/dto/result/RegistrationStartResult.java`
- Move: `registration/RegistrationStatusResult.java` -> `registration/dto/result/RegistrationStatusResult.java`
- Move: `registration/RegistrationCompleteResult.java` -> `registration/dto/result/RegistrationCompleteResult.java`
- Move: `registration/VerificationDispatchResult.java` -> `registration/dto/result/VerificationDispatchResult.java`
- Move: `registration/RegistrationException.java` -> `registration/exception/RegistrationException.java`
- Move: `registration/RegistrationErrorCode.java` -> `registration/enums/RegistrationErrorCode.java`
- Move: `registration/RegistrationStatus.java` -> `registration/enums/RegistrationStatus.java`
- Move: `registration/VerificationChannel.java` -> `registration/enums/VerificationChannel.java`
- Move: `RegistrationInfrastructureConfiguration.java` -> `registration/config/`

- [ ] **Step 1: 移动生命周期 Service 接口和实现**

Controller 和其他 Service 后续只能依赖：

```java
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
```

- [ ] **Step 2: 按 command/query/result 拆分 DTO**

移动时保持 Record 组件名称和顺序完全不变。

- [ ] **Step 3: 更新所有异常、枚举和配置引用**

异常码不得更名；配置属性前缀不得改变。

### Task 9: 重构 registration flow

**Files:**
- Move: flow records -> `registration/flow/domain/`
- Move: `RegistrationFlowStore.java` -> `registration/flow/store/RegistrationFlowStore.java`
- Move: `RedisRegistrationFlowStore.java` -> `registration/flow/store/impl/RedisRegistrationFlowStore.java`
- Move: `RegistrationTokenProtector.java`、`ProtectedRegistrationAccess.java`、`RegistrationAccess.java` -> `registration/flow/security/`
- Move: flow tests to matching packages

- [ ] **Step 1: 分离领域对象、Store 与安全访问对象**

`domain` 不得依赖 Redis 或 Spring，`store` 只声明持久化接口，`store.impl` 才允许依赖 `StringRedisTemplate`。

- [ ] **Step 2: 保持 Redis Key 和 Lua 路径不变**

禁止修改：

```text
ai-temperate-service/src/main/resources/lua/registration/
```

以及所有 `RedisKeyFactory` 生成规则。

- [ ] **Step 3: 更新 RegistrationServiceImpl import**

只更新类型位置，不调整注册流程调用顺序。

### Task 10: 重构 registration verification 与 components

**Files:**
- Move: `TurnstileVerificationService.java` -> `registration/service/turnstile/TurnstileVerificationService.java`
- Move: `registration/turnstile/impl/TurnstileVerificationServiceImpl.java` -> `registration/service/turnstile/impl/TurnstileVerificationServiceImpl.java`
- Move: `VerificationDeliveryStrategy.java` -> `registration/verification/delivery/strategy/`
- Move: `registration/verification/EmailVerificationDeliveryStrategy.java` -> `registration/verification/delivery/strategy/impl/EmailVerificationDeliveryStrategy.java`
- Move: `registration/verification/SmsVerificationDeliveryStrategy.java` -> `registration/verification/delivery/strategy/impl/SmsVerificationDeliveryStrategy.java`
- Move: `registration/verification/VerificationDeliveryRegistry.java` -> `registration/verification/delivery/registry/VerificationDeliveryRegistry.java`
- Move: `registration/verification/VerificationDeliveryCoordinator.java` -> `registration/verification/delivery/coordinator/VerificationDeliveryCoordinator.java`
- Move: `registration/verification/VerificationDeliveryRetrier.java` -> `registration/verification/delivery/retry/VerificationDeliveryRetrier.java`
- Move: `registration/verification/VerificationDeliveryObserver.java` -> `registration/verification/delivery/observer/VerificationDeliveryObserver.java`
- Move: `registration/verification/MicrometerVerificationDeliveryObserver.java` -> `registration/verification/delivery/observer/impl/MicrometerVerificationDeliveryObserver.java`
- Move: `registration/verification/VerificationDeliveryRequest.java` -> `registration/verification/delivery/dto/VerificationDeliveryRequest.java`
- Move: `registration/VerificationCodeGenerator.java` -> `registration/verification/generator/VerificationCodeGenerator.java`
- Move: `registration/SecureVerificationCodeGenerator.java` -> `registration/verification/generator/impl/SecureVerificationCodeGenerator.java`
- Move: `registration/RegistrationIdGenerator.java` -> `registration/component/id/RegistrationIdGenerator.java`
- Move: `registration/impl/SnowflakeRegistrationIdGenerator.java` -> `registration/component/id/impl/SnowflakeRegistrationIdGenerator.java`
- Move: `registration/RegistrationTokenGenerator.java` -> `registration/component/token/RegistrationTokenGenerator.java`
- Move: `registration/SecureRegistrationTokenGenerator.java` -> `registration/component/token/impl/SecureRegistrationTokenGenerator.java`
- Move: `registration/RegistrationInputNormalizer.java` -> `registration/component/normalizer/RegistrationInputNormalizer.java`
- Move: `registration/RegistrationPasswordPolicy.java` -> `registration/component/policy/RegistrationPasswordPolicy.java`
- Move: `registration/RegistrationAfterCommitExecutor.java` -> `registration/component/executor/RegistrationAfterCommitExecutor.java`
- Move: `registration/impl/SpringRegistrationAfterCommitExecutor.java` -> `registration/component/executor/impl/SpringRegistrationAfterCommitExecutor.java`
- Move: `registration/RegistrationCleanupObserver.java` -> `registration/component/observer/RegistrationCleanupObserver.java`
- Move: `registration/impl/MicrometerRegistrationCleanupObserver.java` -> `registration/component/observer/impl/MicrometerRegistrationCleanupObserver.java`

- [ ] **Step 1: 拆分验证码投递策略体系**

Registry 仍通过 Spring 注入：

```java
Map<String, VerificationDeliveryStrategy>
```

不得改变策略 Bean 名、枚举选择逻辑或不可变 Map 行为。

- [ ] **Step 2: 拆分生成器与通用组件**

接口位于能力父包，实现进入 `impl`，例如：

```text
registration/component/id/RegistrationIdGenerator.java
registration/component/id/impl/SnowflakeRegistrationIdGenerator.java
```

- [ ] **Step 3: 更新 RegistrationInfrastructureConfiguration import**

配置类继续构造相同 Bean，不调整配置属性名称或默认值。

### Task 11: 重构 service 根配置与模块标记

**Files:**
- Move: `service/ServiceInfrastructureConfiguration.java` -> `service/config/ServiceInfrastructureConfiguration.java`
- Move: `service/ServiceModule.java` -> `service/module/ServiceModule.java`
- Modify: component scanning/import references

- [ ] **Step 1: 移动共享配置和模块标记**

共享 `Clock` Bean 名和数量保持不变，禁止重新引入多个 Clock Bean。

- [ ] **Step 2: 检查全部配置类**

```powershell
rg -n "class .*Configuration|@Configuration" ai-temperate-* -g "*.java"
```

期望：配置类全部位于以 `.config` 或 `.config.properties` 结尾的包。

### Task 12: 重构 web 模块并预留拦截器结构

**Files:**
- Move: `config/SecurityConfiguration.java` -> `web/auth/config/SecurityConfiguration.java`
- Move: `config/properties/AuthSecurityProperties.java` -> `web/auth/config/properties/AuthSecurityProperties.java`
- Move: `config/RabbitDurabilityConfiguration.java` -> `web/rabbitmq/config/RabbitDurabilityConfiguration.java`
- Move: `web/HealthController.java` -> `web/health/controller/HealthController.java`
- Keep: `AiTemperateApplication.java` at root package
- Create directories only when interceptor classes are actually added: `web/interceptor/session`、`csrf`、`registration`、`recovery`、`config`
- Move: web tests to mirror target packages

- [ ] **Step 1: 按 auth、rabbitmq、health 业务能力移动 Web 类**

`SecurityConfiguration` 和 `AuthSecurityProperties` 同属 `web.auth.config` 能力；RabbitMQ 配置不得继续与认证配置混放。

- [ ] **Step 2: 不创建空拦截器类**

在 `docs/architecture/java-package-conventions.md` 记录未来路径：

```text
web/interceptor/session/SessionAuthenticationInterceptor.java
web/interceptor/csrf/CsrfInterceptor.java
web/interceptor/registration/RegistrationFlowInterceptor.java
web/interceptor/recovery/RecoveryFlowInterceptor.java
web/interceptor/config/WebInterceptorConfiguration.java
```

- [ ] **Step 3: 检查 Spring 扫描边界**

确认启动类仍为：

```java
package com.example.temperate;
```

不得把启动类移动到 `web` 子包，否则会漏扫兄弟模块 Bean。

### Task 13: 同步测试源码、反射类名和文档引用

**Files:**
- Modify/Move: all files under `*/src/test/java`
- Modify: `README.md`
- Modify: `docs/**/*.md`
- Modify: tests containing `Class.forName(...)` or source-path assertions

- [ ] **Step 1: 镜像生产包移动测试**

测试类应与被测类处于相同业务能力路径；需要访问 package-private 构造器的测试必须移动到完全相同 package。

- [ ] **Step 2: 更新字符串形式的完整类名**

```powershell
rg -n "Class\.forName|com\.example\.temperate\.(common|model|mapper|service|config|web)" . -g "*.java" -g "*.xml" -g "*.md"
```

逐项判断并更新，不修改历史 SQL 注释中的普通文字。

- [ ] **Step 3: 检查测试资源路径**

MyBatis XML、Lua 和 YAML 资源只在 Java 代码硬编码 classpath 发生变化时更新；本重构默认保持资源目录不变。

### Task 14: 执行唯一一次静态与编译审查

**Files:**
- Review: all modified Java/XML/Markdown files

- [ ] **Step 1: 检查旧包和旧目录残留**

```powershell
rg -n "common\.springutils|common\.utils|service\.auth\.authentication|service\.registration\.impl|service\.registration\.verification|com\.example\.temperate\.config" . -g "*.java" -g "*.xml"
```

期望：无旧 package/import/namespace；若文档描述迁移前路径，必须明确标记为历史示例。

- [ ] **Step 2: 检查包声明与目录一致性**

使用 PowerShell 遍历 `src/main/java` 和 `src/test/java`，将目录转换为期望 package，并与每个 Java 文件第一条 `package` 声明比较；任何不一致均停止交付。

- [ ] **Step 3: 检查同包职责纯度**

确认：

```text
*.service.impl 只包含 Service 实现
*.dto.command 只包含 Command
*.dto.query 只包含 Query
*.dto.result 只包含 Result
*.config 只包含配置
*.exception 只包含异常和错误码
*.enums 只包含枚举
*.store.impl 只包含 Store 实现
*.strategy.impl 只包含策略实现
```

- [ ] **Step 4: 只编译，不运行测试**

Run:

```powershell
mvn -DskipTests test-compile
```

Expected: `BUILD SUCCESS`，主源码和测试源码均成功编译，测试执行数量为 0。

- [ ] **Step 5: 人工审查一次**

检查是否存在：字段注入、反向模块依赖、Service 直接注入 Impl、Mapper namespace 错误、重复类、空目录、错误的 `interface` 包、未使用 import、YAML 改动或业务逻辑改动。发现问题直接修正后重新执行 Step 1～4，不开展额外功能测试。

## 完成标准

- 所有 Java 包均遵循“业务能力 → 类型 → impl”。
- 同一叶子包不再混放接口、实现、DTO、配置、异常和枚举。
- Spring Bean 名称、Service 接口、DTO 字段、MyBatis SQL、Redis Lua 和配置属性保持不变。
- `mvn -DskipTests test-compile` 成功。
- 未运行任何详细测试或 Testcontainers。
