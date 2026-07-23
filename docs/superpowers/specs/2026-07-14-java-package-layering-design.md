# Java 包结构统一分层设计

## 目标

对 `ai-temperate-common`、`ai-temperate-model`、`ai-temperate-mapper`、`ai-temperate-service` 和 `ai-temperate-web` 的 Java 包进行统一重构，使目录首先表达业务能力，其次表达代码角色，避免接口、实现类、DTO、枚举、异常、配置和组件混放。

本次只调整目录、`package`、`import`、MyBatis namespace/type 引用和测试包路径，不改变业务行为、公共 API 字段、SQL、Redis Key、Lua、Cookie 或认证流程。

## 统一规则

1. 顶层先按业务能力拆分，例如 `auth/login`、`auth/session`、`registration/flow`、`registration/verification`。
2. 业务能力内部再按角色拆分：`service`、`dto/command`、`dto/query`、`dto/result`、`domain`、`entity`、`store`、`strategy`、`registry`、`observer`、`component`、`config`、`exception`、`enums`。
3. Service 接口放在对应业务能力的 `service` 包，唯一实现放在 `service/impl`。
4. 不使用 `interface` 作为包名；它是 Java 关键字。接口所在父包只放同类接口，实现统一进入 `impl`。
5. `impl` 包只放接口实现类，不放 DTO、枚举、配置或普通工具。
6. DTO 根据方向继续拆分为 `command`、`query`、`result`；请求级内部值对象可以放 `dto/internal`。
7. Entity 只放 `model/**/entity`，领域只读上下文放 `model/**/domain`，枚举放 `enums`。
8. 配置类跟随所属业务，例如 `service/auth/config`、`service/registration/config`、`web/auth/config`；拦截器注册配置放 `web/interceptor/config`。
9. 观察器、策略、Store、生成器等均采用“接口父包 + `impl` 子包”。
10. 不创建尚无代码的空包；恢复密码和拦截器代码后续创建时直接遵循本规范。

## 模块目标结构

### common

```text
common/
├─ codec/id/
├─ redis/key/
├─ security/hmac/
├─ validation/email/
├─ bloom/counting/
├─ jwt/component/
├─ mail/component/
├─ id/snowflake/{component,config}/
├─ sms/factory/
├─ async/config/
└─ module/
```

### model

```text
model/
├─ auth/domain/
├─ auth/enums/
├─ user/entity/
└─ module/
```

### mapper

```text
mapper/
├─ user/identity/
├─ user/profile/
└─ module/
```

### service

```text
service/
├─ auth/
│  ├─ config/
│  ├─ protection/component/
│  ├─ login/{service,dto,component,exception,enums,audit,limit}/
│  └─ session/{authentication,refresh,token}/
├─ registration/
│  ├─ service/lifecycle/
│  ├─ dto/{command,query,result}/
│  ├─ flow/{domain,store,security}/
│  ├─ verification/{delivery,generator}/
│  ├─ component/{id,token,normalizer,policy,executor,observer}/
│  ├─ config/
│  ├─ exception/
│  └─ enums/
└─ config/
```

### web

```text
web/
├─ auth/config/
├─ auth/config/properties/
├─ rabbitmq/config/
├─ health/controller/
├─ interceptor/{session,csrf,registration,recovery}/
├─ interceptor/config/
├─ response/
└─ exception/
```

`AiTemperateApplication` 保留在 `com.example.temperate` 根包，确保组件扫描覆盖全部模块。

## 验证边界

用户要求本次不执行详细测试。迁移完成后仅执行一次静态与编译审查：

- 使用 `rg` 检查旧包名、错误目录、重复类和未迁移引用。
- 使用 `mvn -DskipTests test-compile` 编译主代码与测试代码，但不运行测试。
- 不执行 `mvn clean verify`、Testcontainers、Android/iOS/H5 冒烟测试。

## 非目标

- 不拆分现有 Service 方法或改变接口职责。
- 不修复登录事务、CAS、拦截器和密码找回等功能问题。
- 不修改数据库表、SQL 语义、Redis Lua、YAML 配置值或前端代码。
- 不新增空的 Controller、Interceptor、DTO 或占位实现。
