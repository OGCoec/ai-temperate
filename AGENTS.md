# ai-temperate 项目强制工程规范

本文件适用于 `C:\Users\damn\Desktop\ai-temperate` 及其全部子目录。所有人工开发、Codex 和其他自动化代理在修改本项目时必须遵守本规范。

规范关键词：

- **必须 / 禁止**：强制规则，不得自行绕过。
- **应该**：默认规则，只有明确理由时才允许例外。
- **可以**：可选实现。
- 任何需要违反强制规则的改动，必须先编写 ADR，说明原因、风险、替代方案和回滚方式。

## 1. 项目架构与依赖方向

模块依赖方向固定为：

```text
ai-temperate-web
  -> ai-temperate-service
    -> ai-temperate-mapper
      -> ai-temperate-model
        -> ai-temperate-common
```

- 下层模块禁止反向依赖上层模块。
- `ai-temperate-web` 是唯一 Spring Boot 启动和可执行 JAR 模块。
- 业务逻辑放在 `service`，数据库访问放在 `mapper`，领域对象放在 `model`。
- 不得在 `common` 中放入特定业务流程。
- 子模块只声明自己直接使用的依赖，不得依赖上层模块提供的传递依赖。
- 第三方版本统一在父 POM 的 `dependencyManagement` 中管理。

### 1.1 Service 接口与 Impl 实现规范

- 所有业务 Service 必须采用“接口 + 实现类”结构，即使当前只有一个实现也不得省略接口。
- 接口命名使用业务名称，例如 `UserLoginService`，禁止使用 `IUserLoginService` 形式。
- 实现类固定命名为接口名加 `Impl`，例如 `UserLoginServiceImpl`。
- Service 接口放在所属业务包中，实现类放在对应 `impl` 子包中。
- `@Service` 只能标注在实现类上，不得标注在接口上。
- Controller、其他 Service 和调度器只能依赖 Service 接口，禁止注入具体 `Impl`。
- 必须使用构造器注入和 `final` 字段，禁止字段注入和 `@Autowired` 可变字段。
- 实现类必须保持无状态；禁止在单例 Service 中保存请求级可变数据。
- 事务边界放在 Service 实现类的公开业务方法上。
- 禁止同一个实现类内部通过 `this.method()` 调用依赖 Spring AOP 的事务、缓存、异步或重试方法；此类方法必须移动到独立 Bean 后通过代理调用。
- Entity、DTO、VO 和 Service 实现类必须分离；`Impl` 是业务实现类，不是数据库实体类。

推荐包结构：

```text
service/user/UserLoginService.java
service/user/impl/UserLoginServiceImpl.java
```

示例：

```java
public interface UserLoginService {
    LoginResult login(LoginCommand command);
}

@Service
public final class UserLoginServiceImpl implements UserLoginService {
    private final UserLoginIdentityMapper mapper;

    public UserLoginServiceImpl(UserLoginIdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        // orchestration
    }
}
```

### 1.2 多实现策略与 Map 注入规范

同一个策略接口存在多个实现时，Spring 必须一次性注入全部实现：

```java
Map<String, LoginStrategy>
```

禁止：

- 在业务流程中使用连续 `if/else` 或 `switch` 创建和选择策略实现。
- 手工 `new` 策略实现。
- 使用 `ApplicationContext.getBean()` 动态查找策略。
- 把客户端输入直接当作 Spring Bean 名称。
- Controller 直接持有全部策略 Map。

每一个策略实现必须：

- 使用 `@Component` 或 `@Service` 注册为 Spring Bean。
- 实现统一策略接口。
- 返回稳定的枚举类型，例如 `LoginStrategyType`，不得只依赖类名推导类型。
- 保持无状态和线程安全。
- 对自己支持的输入执行边界校验。

所有策略必须通过统一 Registry/Factory 选择。Registry 构造器接收 Spring 注入的 `Map<String, Strategy>`，再转换为不可变的 `EnumMap<StrategyType, Strategy>`：

```java
public interface LoginStrategy {
    LoginStrategyType type();
    LoginResult login(LoginCommand command);
}

@Component("emailLoginStrategy")
public final class EmailLoginStrategy implements LoginStrategy {
    @Override
    public LoginStrategyType type() {
        return LoginStrategyType.EMAIL;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        // email login
    }
}

@Component
public final class LoginStrategyRegistry {
    private final Map<LoginStrategyType, LoginStrategy> strategies;

    public LoginStrategyRegistry(Map<String, LoginStrategy> strategyBeans) {
        EnumMap<LoginStrategyType, LoginStrategy> registered =
                new EnumMap<>(LoginStrategyType.class);

        for (LoginStrategy strategy : strategyBeans.values()) {
            LoginStrategy previous = registered.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate login strategy: " + strategy.type());
            }
        }

        this.strategies = Map.copyOf(registered);
    }

    public LoginStrategy getRequired(LoginStrategyType type) {
        LoginStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported login strategy: " + type);
        }
        return strategy;
    }
}
```

Registry 必须满足：

- 启动时检查同一枚举类型是否重复注册，重复时立即启动失败。
- 业务不支持的策略类型必须返回受控业务错误，禁止返回 `null`。
- 注册完成后 Map 必须不可变。
- 禁止依赖 Map 迭代顺序决定业务优先级；需要优先级时使用明确字段或 `@Order` 并单独建模。
- 新增策略实现后，原有调用代码不应修改，只增加实现类和测试。
- 必须测试所有策略已注册、重复类型失败、未知类型失败以及每种类型选择正确实现。

### 1.3 YAML 配置文件规范

所有 `.yml` 和 `.yaml` 文件必须采用“一行中文注释，下一行对应配置”的格式。每一个实际配置行，包括父级节点和列表项，前一行都必须有中文注释。

正确示例：

```yaml
# RabbitMQ AMQP 连接配置。
rabbitmq:
  # RabbitMQ AMQP 服务端口。
  port: ${RABBITMQ_PORT:5673}
```

错误示例：

```yaml
rabbitmq:
  port: 5673
```

强制要求：

- 注释必须说明配置目的、默认行为或安全要求，禁止只把配置键翻译成中文。
- 父级节点、叶子配置和数组列表项都必须分别添加中文注释。
- 使用两个空格缩进，禁止 Tab。
- 同一层级禁止重复配置键。
- 逻辑配置组之间可以保留一个空行。
- 主机、端口、账号、密码和密钥必须支持环境变量覆盖。
- 生产密码和 Secret 禁止写入 YAML 默认值。
- 修改 YAML 后必须运行 Spring 上下文测试，确认配置可以解析和绑定。
- Code Review 必须检查新增 YAML 是否满足“注释行紧邻配置行”。

### 1.4 Java 中文注释规范

Java 注释用于说明非直观机制的“为什么”、安全或一致性不变量，必须使用中文；禁止逐行翻译语法、变量名或显而易见的控制流。

仓库内所有现有及未来新增或修改的 Java 代码，出现以下任一情况必须添加中文注释：

- **逻辑实现原理**：算法、状态机、规则优先级、降级路径或其他不能从代码表面直接推导的实现原因。
- **复杂业务逻辑**：跨多个步骤的业务编排、执行顺序、前置条件、失败后果或不可变业务约束。
- **并发安全实现原理**：锁、CAS、原子操作、幂等键、可见性、竞态规避及其所保证的并发不变量。
- **边界转换**：外部请求、DTO、领域对象、数据库实体、缓存值、消息体之间的转换规则，以及编码、精度、时区、空值或脱敏处理原因。
- **安全保证原理**：认证、授权、Token、密码、CSRF、敏感数据、输入校验和防重放等代码所防御的风险，以及校验顺序不可调整的原因。
- **事务原理**：事务边界、写入顺序、回滚范围、提交后缓存删除、消息发布或异步动作的时机，以及由此保证的一致性边界。

注释位置和内容必须满足：

- 每个现有及未来新增或修改的非自动生成 Java 源文件，必须在主要顶级类型（`class`、`interface`、`enum` 或 `record`）前使用中文 JavaDoc；第一句必须明确说明该文件“是来做什么的”，至少交代其业务或技术职责、处理的问题以及主要协作边界，禁止只翻译类名、包名或注解名称。
- 类或公开方法整体承担上述机制时，必须使用中文 JavaDoc 说明职责、关键不变量和不负责的边界。
- 锁、事务、关键分支、边界转换和安全校验等局部机制，必须在对应代码块前使用紧邻的中文 `//` 注释。
- 注释必须说明设计意图、顺序约束、不变量、失败影响或安全原因中的至少一项；禁止只复述“调用某方法”“判断某变量”等表面行为。
- 代码修改导致注释不再准确时，必须在同一次修改中更新或删除注释；禁止保留过期注释。
- 注释中禁止出现明文密码、Token、Secret、验证码、完整手机号、完整邮箱或可用于绕过安全控制的细节。

以下场景默认不强制添加注释：简单 CRUD、Getter/Setter、显而易见的空值或长度校验、框架标准注解、直接字段映射和无业务分支的样板代码。不得为了满足数量要求给每一行 Java 代码添加注释。

### 1.5 前后端展示代码完全分离规范

- Java 后端源码中禁止出现 HTML、CSS、JavaScript、Vue、React、UniApp 或其他前端展示代码，包括 Java Text Block、字符串拼接、注解字符串和运行时生成脚本。
- `Controller` 只能负责请求参数校验、认证授权、HTTP 状态与响应头编排，以及返回 classpath 静态资源、视图名称或结构化 DTO；禁止在 `Controller` 中构造、替换或转义前端页面内容。
- H5、Android WebView 和其他浏览器页面必须存放在前端工程，或 `ai-temperate-web/src/main/resources/verification-pages` 等明确的页面资源目录中；HTML、CSS 和 JavaScript 的实现与维护必须在对应前端文件内完成。需要先经过 Controller 安全校验的页面禁止放入可绕过该入口直接访问的公开静态目录。
- 页面需要运行时数据时，必须通过受控 JSON API、经过白名单校验的 Query/Fragment 或框架标准视图模型传递；禁止为了注入变量而把前端模板重新拼接回 Java 代码。
- Secret、一次性 Token、完整 challenge、密码、验证码和敏感身份信息禁止写入静态资源、页面源码、日志或非必要 URL；公开 Site Key 也不得输出到日志。
- 静态页面必须保持无缓存、安全响应头和 CSP 等原有安全边界；资源拆分不得绕过服务端参数校验、认证、Siteverify 或一次性 Token 防重放。
- 新增或修改返回页面的 Controller 时，必须提供契约测试，确认响应来自独立资源文件，并检查 Controller 源码不包含 `<html>`、`<style>`、`<script>` 或前端 SDK 地址。
- 因框架限制确需违反本节时，必须先编写 ADR，说明无法使用静态资源或标准视图机制的原因、安全影响、替代方案与回滚方式。

## 2. API 与 PathVariable 规范

### 2.1 Base64URL 资源 ID

所有携带业务资源 ID 的 `PathVariable` 必须使用 Base64URL，禁止直接暴露数据库 `BIGINT`。

统一编码器：

```java
Base64.getUrlEncoder().withoutPadding()
```

内部正数 `Long` 的编码规则固定为：

```text
正数 Long
-> 8 字节大端序
-> Base64URL without padding
-> 固定 11 个字符
```

合法格式：

```regex
^[A-Za-z0-9_-]{11}$
```

必须满足：

- 禁止使用标准 Base64 的 `+`、`/` 和填充字符 `=`。
- 解码后的 ID 必须大于 0。
- 解码后必须重新编码并与原输入完全一致，防止非规范编码。
- 格式错误返回 HTTP 400；格式正确但资源不存在返回 HTTP 404。
- Controller 禁止自行编解码，必须使用统一 `PublicIdCodec` 和 Spring Converter。
- API 响应中的资源 ID、`Location` Header 和后续请求 PathVariable 必须使用相同编码。
- OpenAPI 必须声明长度、正则表达式和示例。
- Base64URL 只是编码，不是加密；所有资源仍必须执行认证和资源级授权。

枚举、状态、日期等非资源 ID PathVariable 应使用可读值和白名单校验，不得为了形式统一而强制编码。

如果未来要求资源 ID 不可枚举，必须增加随机 `public_id`（推荐 UUIDv7 或 128-bit 随机值），不能把 Base64URL 当作防枚举方案。

### 2.2 Swagger/OpenAPI Controller Tag 规范

- 每一个对外提供 HTTP API 的 `@RestController` 都必须在类级别添加 `io.swagger.v3.oas.annotations.tags.Tag`；`@RestControllerAdvice` 等全局异常处理类不属于 API Controller，不添加业务 Tag。
- `@Tag.name` 必须使用稳定、清晰的中文业务分组名称，例如“认证-用户登录”“认证-会话与令牌”“系统-健康检查”，禁止只使用英文类名、URL 或数据库表名。
- `@Tag.description` 必须使用中文详细说明该 Controller 的业务职责、接口覆盖范围、适用客户端或平台、认证与安全边界，以及明确不负责的内容；禁止只写“接口说明”或重复类名。
- 同一 Controller 只设置一个主要业务 Tag；跨业务 Controller 必须选择实际负责的主要能力归类，禁止依赖 Tag 迭代顺序表达业务优先级。
- 每一个公开 API 方法都应该添加 `@Operation`，`summary` 使用简洁中文说明实际动作；需要调试请求时，应在 `description`、`@Parameter`、`@RequestBody` 或 `@Schema` 中补充请求头、请求体、响应字段、认证要求、错误条件和脱敏要求。
- OpenAPI 文档、Swagger UI 和 Apifox 导入内容禁止出现明文密码、验证码、Token、Secret、AccessKey、完整手机号或邮箱等敏感示例；敏感字段必须使用脱敏占位符，并按实际读写方向标注。
- Controller 路径、请求参数、响应中的资源 ID 仍必须遵守 Base64URL、认证和资源级授权规范；OpenAPI 注解不得为了调试方便而降低接口校验或安全要求。
- 新增或修改 Controller 时，Tag 和 Operation 注解应与实际路由及业务行为同步更新。第一阶段可以先交付代码，不自动运行 Swagger、Apifox 或集成测试；进入第二阶段安全测试后，再由用户确认是否进行 OpenAPI 文档生成和接口调试验证。

## 3. 密码与 Spring Security 规范

- 密码处理必须使用 Spring Security `PasswordEncoder`。
- 禁止自行实现 MD5、SHA、盐值拼接、重复哈希或密码加解密算法。
- `service` 中使用 `PasswordEncoder` 接口，不得直接依赖某个实现类。
- 实现密码逻辑前，`ai-temperate-service` 必须直接声明 `spring-security-crypto`，不得依靠 `web` 模块的传递依赖。
- 全项目只能有一个统一的 `PasswordEncoder` Bean。
- 默认使用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`，保留 `{bcrypt}` 等算法标识和未来升级能力。
- 创建或修改密码时只能调用 `passwordEncoder.encode(rawPassword)`。
- 登录校验只能调用 `passwordEncoder.matches(rawPassword, encodedPassword)`，禁止字符串比较。
- 登录成功后如 `passwordEncoder.upgradeEncoding(encodedPassword)` 返回 `true`，应该重新编码并更新数据库。
- 密码哈希字段必须至少使用 `VARCHAR(255)`。
- 使用默认 BCrypt 时，密码必须校验 UTF-8 字节长度，建议 8～72 bytes，禁止静默截断。
- 禁止存储、缓存、记录或发送明文密码。
- 禁止把明文密码或密码哈希放入 Redis、RabbitMQ、日志、异常消息和监控标签。
- `password_hash` 原则上不进入长期缓存。
- 登录和密码验证接口必须具备限流、失败次数控制和审计记录。
- 所有密码相关测试禁止使用真实生产密码或密钥。

## 4. PostgreSQL 规范

### 4.1 单主库与连接池

- 项目只使用一个 PostgreSQL 主库，默认端口 `5431`。
- 禁止分库、分表、从库、读写分离和 `AbstractRoutingDataSource`。
- 每个应用实例只允许初始化一个 HikariCP 连接池。
- 禁止在 Controller、Service、Mapper 或循环中创建 `DataSource`、连接池或数据库客户端。
- 连接池大小必须根据 PostgreSQL `max_connections`、应用实例数和后台任务数量计算。
- 所有数据库连接、Statement 和 ResultSet 必须由框架可靠关闭。

### 4.2 禁止物理外键

本项目当前禁止使用：

```sql
FOREIGN KEY
REFERENCES
```

由于禁止物理外键，必须提供以下补偿：

- 所有逻辑关联字段必须建立普通索引。
- 写入关联数据前必须验证目标记录存在。
- 删除主记录时必须显式处理所有关联数据，禁止假设数据库会级联处理。
- 跨表状态修改必须放在同一 PostgreSQL 本地事务中。
- 每一种逻辑关系必须提供孤儿数据检查 SQL。
- 逻辑关系、删除顺序和恢复方式必须记录在数据库设计文档中。
- 禁止依赖应用层校验宣称绝对关系完整性；无物理外键是本项目明确接受的风险。

### 4.3 禁止 N+1 与逐条 I/O

禁止：

```java
for (Long id : ids) {
    mapper.selectById(id);
}
```

集合参数必须直接传给 Mapper，优先使用：

- `WHERE id = ANY(...)`
- `WHERE id IN (...)`
- 批量 `INSERT ... VALUES`
- `UPDATE ... FROM (VALUES ...)`
- JDBC Batch
- PostgreSQL `COPY`

允许按批次循环，但每一批内部必须是一次批量数据库操作：

```text
默认每批 500 条
根据压测可以调整，但单批不得超过 2000 条
SQL 绑定参数不得接近 PostgreSQL 65535 参数上限
```

是否使用一个事务由业务原子性决定。禁止为了“一次操作”把无限数据放入超大事务。

### 4.4 SQL 安全

- 所有 SQL 必须参数化，禁止拼接用户输入。
- 动态排序字段、表名和列名必须使用服务端白名单。
- 列表查询必须分页并设置最大 page size。
- 禁止生产接口执行无边界全表扫描。
- 批量删除和更新必须包含明确条件，并验证影响行数。

## 5. PostgreSQL 索引规范

- 索引必须由真实查询模式驱动。
- 新增或修改索引必须使用 `EXPLAIN (ANALYZE, BUFFERS)` 验证。
- 禁止给没有查询需求的字段预先创建索引。
- 复合索引通常按照“等值条件 -> 范围条件 -> 排序字段”排列。
- 低基数字段不得单独建立普通索引，除非使用有效的部分索引或与其他字段组成复合索引。
- 唯一性约束与查询性能索引必须分别考虑；GIN 不能替代唯一 B-tree。

查询类型与索引：

| 查询类型 | 索引类型 |
| --- | --- |
| 精确查询 | B-tree |
| 范围和排序 | B-tree / 复合 B-tree |
| 前缀 `abc%` | B-tree + `text_pattern_ops` |
| 包含 `%abc%` | `pg_trgm + GIN` |
| 自然语言全文检索 | `tsvector + GIN` |

非前缀模糊查询必须使用 PostgreSQL `pg_trgm`：

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_userloginidentity_email_trgm
    ON userloginidentity
    USING GIN (LOWER(email) gin_trgm_ops);
```

- 查询表达式必须与表达式索引一致，例如使用 `LOWER(email)`。
- 少于 3 个字符的模糊关键词应拒绝或走受限降级查询。
- GIN 会增加写入和磁盘成本，只能用于确实存在的模糊查询。
- 为已有大表创建索引时应该使用 `CREATE INDEX CONCURRENTLY`；该语句不得放入事务块。

当前 `userloginidentity`：

- `id` 使用 B-tree 主键。
- `LOWER(email)` 使用大小写不敏感唯一 B-tree。
- `phone` 使用 `WHERE phone IS NOT NULL` 的部分唯一 B-tree。
- `password_hash` 禁止建立索引。

## 6. Redis Key 设计规范

统一格式：

```text
<项目>:<环境>:<业务域>:<对象>:<版本>:<类型>:<标识>
```

项目短前缀固定为 `ait`：

```text
ait:prod:auth:uli:v1:id:10001
ait:prod:auth:uli:v1:email:<HMAC>
ait:prod:auth:uli:v1:phone:<HMAC>
ait:prod:bloom:uli:v1:bucket:0001
ait:prod:limit:sms:v1:<HMAC>
```

- 命名空间部分必须使用小写 ASCII 和冒号分层。
- Base64URL/HMAC 标识部分允许使用大小写字母、数字、`_`、`-`。
- 所有 Key 必须由统一 `RedisKeyFactory` 生成。
- 禁止在业务代码中使用字符串拼接创建 Key。
- 禁止在 Key 中放入 JSON、URL、Token、明文邮箱、明文手机号或其他敏感信息。
- 邮箱和手机号必须先规范化，再使用 HMAC-SHA256 Base64URL 作为标识。
- 禁止只使用普通 SHA-256 处理手机号等低熵数据，防止字典枚举。

Key 名称按 UTF-8 字节计算：

```text
目标长度 <= 96 bytes
正常上限 <= 128 bytes
超过 128 bytes 必须告警
超过 256 bytes 禁止写入
```

## 7. Redis Value 与序列化规范

`userloginidentity` 整体缓存使用：

```text
Redis String + JSON
```

邮箱/手机号到用户 ID 的映射使用 Redis String。计数、限流、验证码尝试次数等使用 Redis Hash。

- JSON 必须包含 `schemaVersion`。
- 禁止 Java 原生序列化。
- JSON 字段必须稳定，删除或修改字段前必须升级 Key 版本。
- 禁止把所有用户放进同一个 Hash。
- 禁止在用户 JSON 中嵌入订单、登录记录等无限增长集合。
- 禁止缓存明文密码、验证码、AccessKey、OAuth Secret。
- `password_hash` 原则上不缓存。

## 8. Redis BigKey 与 HotKey 规范

单个业务 JSON：

```text
目标 <= 8 KB
超过 10 KB 告警
超过 64 KB 禁止写入
```

集合类型：

```text
超过 1000 个元素开始关注
超过 5000 个元素判定为 BigKey
超过 10000 个元素必须拆分
```

- 大 Key 删除必须使用 `UNLINK`，避免 `DEL` 阻塞主线程。
- 生产环境禁止使用 `KEYS *`，必须使用 `SCAN` 或采样工具。
- 小 Key 在极高 QPS 下仍可能成为 HotKey，必须分别监控 BigKey 和 HotKey。
- 单条 Redis 命令超过 10 ms 必须分析，不得只根据 Key 大小判断风险。

## 9. Redis 集合和批量访问规范

- String 批量读取使用 `MGET`。
- 不要求 TTL 的批量 String 写入可以使用 `MSET`。
- `MSET` 不能设置 TTL；带 TTL 的批量写入必须使用 Pipeline `SET EX` 或 Lua。
- Hash 批量访问使用 `HMGET/HSET`。
- 批量删除使用 `delete(Collection)` 或 `UNLINK`。
- 用户 Refresh Session 全量撤销必须使用 Pipeline 批量发送多 Key `UNLINK`，禁止使用 `MSET`。
- 该撤销路径禁止在 Java 或 Lua 中编写显式 `for` 进行逐 Key Redis I/O；应通过集合批量 API 或底层多参数 `UNLINK` 提交整批 Key。
- 条件判断、计数、扣减和原子选择使用 Lua。
- Pipeline 只减少网络 RTT，不具备事务原子性；Refresh Session 全量撤销允许在提交后出现短暂部分删除状态，必须依赖幂等重试和 TTL 兜底收敛。
- Pipeline 本身不保证最终一致性；禁止将其描述为强一致、Exactly Once 或无条件最终一致。
- Lua 具备原子性，但禁止在单个脚本中处理无界数据。

批量边界：

```text
每批 100～500 个 Key
单批请求或响应总量 <= 1 MB
超过任意边界必须拆分
```

允许循环处理多个批次，但禁止批次内部逐个执行网络请求。

### 9.1 Redis 分布式锁规范

本项目的分布式锁以 Redis 作为协调基础设施，并统一通过 Redisson 提供的锁 API 实现。业务代码禁止自行实现 Redis 分布式锁。

- 所有分布式锁必须通过 Spring 注入的单例 `RedissonClient` 获取 `RLock`；禁止在 Controller、Service、Mapper、循环或请求处理中创建新的 Redisson 客户端。
- 禁止使用原生 `SET NX EX/PX`、`RedisTemplate.opsForValue().setIfAbsent()`、手写 Lua 或其他自定义续租、解锁协议实现分布式锁。
- 上述禁令只针对“锁”。验证码时间片防重放、一次性领取、状态机迁移、计数和其他 Redis 原子业务状态仍可以按对应规范使用 `SET NX` 或 Lua，但不得把这些操作命名或描述为分布式锁。
- 所有分布式锁必须启用 Redisson 看门狗自动续租；获取普通业务锁时必须使用不显式传入 `leaseTime` 的 Redisson API，禁止通过固定租期关闭看门狗，也禁止业务代码自行调度续租任务。
- 看门狗只保证持锁 Redisson 实例正常存活时持续续租，并在实例失联后依靠锁超时释放；禁止把看门狗描述为永久持锁、Exactly Once、最终幂等或跨 Redis 与 PostgreSQL 的原子性保证。
- 获取锁必须设置有界等待时间并处理未取得锁、线程中断和 Redis 异常，禁止业务线程无限等待；锁内禁止执行无界循环或无法限制时长的外部网络调用。
- 解锁必须放在 `finally` 中，并且只能由实际持锁线程执行；解锁前必须确认当前线程仍持有该锁，禁止误删或释放其他线程、其他实例已经取得的锁。
- 所有锁 Key 必须由统一 `RedisKeyFactory` 生成；锁粒度必须绑定最小必要的受保护资源或幂等摘要，禁止直接使用客户端输入作为锁名，也禁止在锁 Key 中放入完整 API Key、Token、邮箱、手机号或其他敏感信息。
- 分布式锁只允许作为并发削峰、减少重复 I/O 或协调单实例任务执行的辅助机制；最终幂等必须由 PostgreSQL 唯一约束、持久化幂等记录和本地事务保证，最终状态一致性必须由业务事实来源裁决。
- API Key 创建等写入 PostgreSQL 的流程必须以客户端 UUIDv4 幂等键和数据库唯一约束作为最终保障；可选 Redisson 锁只能按受保护的幂等摘要过滤并发请求，禁止按整个用户加粗锁，也禁止用锁替代数据库约束。
- 当辅助锁所保护的操作已经具备数据库最终幂等保障时，Redis 不可用应降级到 PostgreSQL 唯一约束裁决；无法安全降级的流程必须返回受控错误，禁止在未取得锁时继续执行无幂等保护的外部副作用。
- 必须监控锁等待时间、获取失败、看门狗续租异常和持锁时间；监控标签禁止包含完整 Redis Key、幂等键、API Key 或无界高基数字段。

## 10. PostgreSQL 与 Redis 一致性

本项目不使用分布式事务、Outbox 或 CDC，采用 Cache-Aside：

```text
提交 PostgreSQL 本地事务
-> 事务提交成功后删除 Redis 缓存
-> 删除失败进行有限次数重试
-> TTL 最终兜底
```

- 缓存删除必须在数据库事务提交后执行，可使用 `TransactionSynchronization` 或 `@TransactionalEventListener(AFTER_COMMIT)`。
- 禁止在数据库事务提交前删除缓存。
- 数据修改后默认删除缓存，不直接覆盖缓存。
- 删除用户身份缓存时，必须一次删除 ID、旧邮箱、新邮箱、旧手机号和新手机号相关 Key。
- 删除失败必须记录指标和结构化日志，禁止静默忽略。
- 重试必须有次数和时间上限，禁止无限重试。
- 正向缓存 TTL 默认 5～15 分钟，并增加约 ±20% 随机抖动。
- 空值缓存 TTL 默认 30～60 秒。
- 强一致读取必须绕过 Redis，直接查询 PostgreSQL。
- 不使用 Outbox/CDC 意味着应用可能在数据库提交后、缓存删除前宕机；项目接受该窗口，并依靠 TTL 恢复，禁止在文档中宣称强一致性。
- 用户密码重置后的 Refresh Session Pipeline 撤销同样不具备跨 PostgreSQL 与 Redis 的原子性；删除中断时由现有有限重试、幂等 `UNLINK` 和 Refresh Token TTL 提供尽力收敛，不得宣称绝对最终一致。

## 11. RabbitMQ 规范

禁止使用“消息先进入磁盘再进入内存”描述 RabbitMQ。业务可靠性必须通过以下机制保证：

- RabbitMQ 默认 AMQP 端口为 `5673`。
- Exchange 必须 durable。
- Queue 必须 durable。
- 重要业务队列应该使用 Quorum Queue。
- 可靠消息必须设置 persistent delivery mode。
- 消息发布前必须设置 `MessageDeliveryMode.PERSISTENT`；禁止依赖调用方临时决定是否持久化。
- 生产者必须开启 Publisher Confirm。
- 需要检测无法路由消息时必须开启 mandatory/Return。
- 消费者必须使用手动 ACK。
- 本地业务事务提交成功后才能 ACK。
- 消费者必须根据 `messageId` 或业务唯一键实现幂等。
- 重试次数必须有限，最终失败进入死信队列。
- 禁止异常消息无限重新入队。
- 消息体建议不超过 256 KB；大对象存入 OSS，消息只传递引用。

每条业务消息必须包含：

```text
messageId
eventType
schemaVersion
occurredAt
traceId
payload
```

本项目不使用 Outbox，因此 PostgreSQL 提交与 RabbitMQ 发布无法形成原子操作。代码和文档必须明确这是已接受风险，禁止宣称 Exactly Once。

## 12. 计数布隆过滤器规范

计数布隆过滤器用于防止缓存穿透，只能判断“肯定不存在”或“可能存在”，不得作为真实数据来源。

- 普通 Redisson `RBloomFilter` 不支持安全删除，禁止用它冒充计数布隆过滤器。
- 计数布隆过滤器必须使用计数器结构和 Lua 实现原子新增、删除、防下溢和防溢出，或经 ADR 批准使用支持删除的服务端模块。
- 禁止把完整计数数组放入一个超大 Redis Key。
- 必须按 Bucket 分片，单 Bucket 建议控制在 1～4 MB。
- 初始化必须分页读取 PostgreSQL，并按批次 Pipeline/Lua 写入；禁止一次请求初始化全部数据。
- 初始化必须使用 `BUILDING -> READY -> ACTIVE` 状态和双版本切换。
- 初始化期间的新增数据必须同步到正在构建的新版本。
- 数据库事务提交后才能增加或减少计数。
- 重复新增和重复删除必须幂等。
- 计数不得小于 0，也不得超过计数器上限。
- Bloom 未就绪、更新失败或状态异常时必须 Fail Open，降级查询 PostgreSQL，禁止直接返回不存在。
- 实现前必须确定预计容量、目标误判率、计数器位宽和重建阈值。

## 13. 验证码、邮箱和短信规范

- 邮箱发送必须使用 Spring Boot `spring-boot-starter-mail` 和 `JavaMailSender`。
- 短信验证码使用项目已声明的阿里云号码认证 SDK。
- 验证码必须使用安全随机数生成器。
- 验证码只能存储摘要或受保护形式，禁止日志输出明文验证码。
- 必须配置发送频率限制、IP 限制、账号限制、验证失败次数和有效期。
- 发送操作应该异步化，但发送结果和失败重试必须可观测。
- 验证成功后必须原子删除验证码，防止重复使用。
- 短信和邮箱接口不得泄露账号是否真实存在，应返回一致的外部响应。

## 14. 日志、隐私和密钥

- 禁止在代码、配置文件、日志或测试中提交生产密码、Token、AccessKey 和 OAuth Secret。
- 密钥必须来自环境变量或 Secret 管理服务。
- 邮箱、手机号、IP、Token 和设备标识必须脱敏后记录。
- 禁止把完整请求体无条件写入日志。
- 外部输入、第三方响应和消息内容都必须视为不可信数据。
- 所有日志应包含 `traceId`；异步消息还应包含 `messageId`。

## 15. 可观测性规范

必须监控：

- PostgreSQL 慢 SQL、连接池使用率、连接等待和事务时间。
- Redis 命令延迟、BigKey、HotKey、缓存命中率和缓存删除失败。
- RabbitMQ Confirm 延迟、Ready、Unacked、重试和死信数量。
- Bloom 容量、误判率、计数器饱和率、构建状态和重建进度。
- 短信、邮件验证码发送成功率、延迟和供应商错误码。
- 登录失败、限流、密码升级和异常账号行为。

监控标签禁止包含明文邮箱、手机号、Token、完整 Redis Key 或无界高基数字段。

## 16. 分阶段交付、测试与完成标准

本项目采用“先交付代码，再进行安全测试”的两阶段工作方式。

### 16.1 第一阶段：代码实现与交付

- 默认先完成用户明确要求的代码修改并交付代码。
- 第一阶段禁止自动运行测试、编译、打包、依赖树分析、安全扫描、集成测试或外部服务连接测试；只有用户在当前任务中明确要求的检查才可以执行。
- 可以随业务代码编写必要的测试代码，但不得在未经用户明确同意的情况下执行这些测试。
- 交付时必须明确列出未执行的验证，不得把“代码已交付”表述为“构建成功”“测试通过”或“功能已验证”。
- 第一阶段代码交付完成后必须停止，等待用户决定是否进入第二阶段。

### 16.2 第二阶段：用户参与的安全测试

- 只有用户明确同意进入安全测试阶段后，才允许运行测试或其他验证命令。
- 执行前必须向用户说明拟运行的命令、测试范围、所需基础设施，以及是否会写入数据库、Redis、RabbitMQ、文件或其他外部状态。
- 必须获得用户对本次测试范围的明确确认；禁止把一次授权扩展到未说明的测试、扫描或外部连接。
- 测试必须优先使用隔离环境、测试配置和非生产数据，禁止连接生产数据库、生产 Redis、生产 RabbitMQ 或其他生产服务。
- 测试结束后必须基于本次新产生的证据报告结果，并区分通过、失败、跳过和未执行项目。

相关修改应该准备对应的测试覆盖：

- Base64URL：正常值、非法字符、带 `=`、错误长度、负数、0、非规范编码。
- 密码：encode/matches、不匹配、算法标识、`upgradeEncoding`、长度边界。
- PostgreSQL：批量 Mapper、事务回滚、影响行数、孤儿数据检查。
- 索引：关键查询的 `EXPLAIN (ANALYZE, BUFFERS)`。
- Redis：Key 长度、KeyFactory、批量边界、TTL、删除失败和序列化版本。
- 缓存一致性：数据库提交成功但 Redis 删除失败、并发旧值回填、空值缓存。
- RabbitMQ：Publisher Confirm、重复消费、手动 ACK、有限重试和死信。
- Bloom：重复新增、重复删除、计数下溢、构建期间并发写入和 Fail Open。
- 验证码：过期、重复验证、失败次数、频率限制和并发消费。

以下命令属于第二阶段的完整验证候选项，不在第一阶段自动执行；只有用户明确批准后才可以运行：

```powershell
mvn clean verify
mvn dependency:tree
```

不得在没有新验证证据的情况下宣称构建成功、测试通过或功能已经验证完成。第一阶段可以说明代码实现和交付已经完成，但必须同时说明测试尚未执行。

## 17. 代码评审检查项

修改代码前后必须检查：

- Controller、Service、Mapper 和其他 Java 后端源码是否包含 HTML、CSS、JavaScript、前端模板字符串或前端 SDK 地址；页面响应是否来自独立资源文件。
- 是否在循环中调用 Mapper、Redis、MQ 或外部 API。
- 是否可以改成批量 SQL、MGET/MSET、Pipeline 或 Lua。
- 分布式锁是否统一通过 Redisson `RLock` 和看门狗实现，是否出现原生 `SET NX EX/PX`、`setIfAbsent()`、手写 Lua、自定义续租或固定 `leaseTime` 实现锁。
- 分布式锁是否只承担辅助并发过滤，数据库写入是否仍由唯一约束、持久化幂等记录和本地事务提供最终保障。
- 是否创建了新的数据库连接池或数据源。
- 是否出现物理外键。
- Service 是否采用接口 + `Impl`，调用方是否只依赖接口。
- 多策略实现是否通过 Spring 注入 `Map<String, Strategy>` 并由统一 Registry 选择。
- 是否出现字段注入、手工 `new` 策略、`ApplicationContext.getBean()` 或策略 `if/switch` 分发。
- YAML 是否为每一个配置行提供了紧邻的中文注释。
- 所有 Java 源文件的主要顶级类型，是否均以中文 JavaDoc 第一段清楚说明该文件的用途、职责与主要协作边界，而非仅重复类名。
- 所有实际包含关键机制的 Java 文件，是否对逻辑原理、复杂业务、并发安全、边界转换、安全保证或事务原理提供了准确的中文注释，且未用注释重复代码表面含义。
- 是否暴露了未编码的数据库 ID。
- 是否使用了标准 Base64 而不是无填充 Base64URL。
- 是否自行实现密码哈希或比较密码字符串。
- 是否把密码、验证码或 PII 写入缓存、消息和日志。
- 是否新增无查询依据的索引。
- 是否存在无边界查询、集合、Pipeline、Lua 或事务。
- 是否错误宣称缓存、消息或 Bloom 具备强一致性。
- 是否补充了必要测试和文档。

## Execution policy

- All implementation tasks must be executed by the root agent.
- Do not spawn, delegate to, or wait for subagents.
- Do not invoke `subagent-driven-development`.
- Do not automatically run code-review or security-review skills.
- Do not split a task into a serialized subagent pipeline.
- Default to the two-stage workflow defined in Section 16: deliver code first, then perform safe verification with the user.
- During the first stage, do not run tests, compilation, packaging, dependency analysis, security scans, integration checks, or external-service checks unless the user explicitly requests them for the current task.
- Before entering the second stage, disclose the proposed commands, scope, infrastructure dependencies, and possible state changes, then obtain the user's explicit confirmation.
- Perform only the verification explicitly requested by the user.
- Report unrelated problems without fixing them.
- Stop immediately after the requested implementation and verification finish.
