# TOTP 第二阶段安全测试计划

## 1. 目标与边界

本阶段验证已经实现的普通用户 TOTP 开启、轮换、关闭和二次登录流程。测试重点是：第一因子完成后不会提前签发正式会话、新密钥确认前不覆盖数据库、Redis 短期状态具备过期/限次/一次性语义、密钥只以 AES-256-GCM 密文持久化，以及 H5/Android 传输边界正确。

本阶段第一轮只新增或完善测试代码并执行验证，不主动修改生产业务实现。若测试发现实现缺陷，先记录失败证据、影响范围和建议补丁，在检查点向用户报告后再决定是否修复。

禁止连接生产 PostgreSQL、Redis、RabbitMQ、邮件、短信或其他外部业务服务。自动化集成测试只允许使用 Testcontainers 临时容器；没有 Docker 时必须明确标记为跳过，不得回退到本机现有端口。

## 2. 基础设施与写入说明

| 项目 | 用途 | 可能写入 | 安全边界 |
| --- | --- | --- | --- |
| Maven/Surefire | Java 单元、契约和上下文测试 | 各模块 `target/`、本地 Maven 缓存 | 不写业务数据库；`application-test.yml` 已排除真实 PostgreSQL、Redis、RabbitMQ 自动配置 |
| Node test runner | 前端契约测试 | 通常只产生控制台结果，可能读取 npm 缓存 | 不启动浏览器，不写业务状态 |
| Docker/Testcontainers | PostgreSQL 15、Redis 7 临时集成环境 | 拉取镜像、创建临时容器和临时卷 | 只使用随机映射端口；测试结束自动销毁；禁止绑定生产配置 |
| 完整 Maven 回归 | 全仓库 Java 验证 | `target/`、可能拉取测试镜像 | 仓库其他集成测试可能启动临时 PostgreSQL、Redis、RabbitMQ 容器，执行前单独确认 Docker 范围 |
| npm audit（可选） | 依赖漏洞报告 | 不修改 lockfile，但会把依赖清单发送给 npm registry | 仅在用户明确批准联网审计后执行；禁止 `npm audit fix` |
| 外部 Chrome（可选） | 最终人工端到端验收 | 仅写隔离容器中的测试用户、测试会话和 TOTP 状态 | 只能连接浏览器类型为 `extension` 的外部 Chrome；禁止 Codex 内置浏览器 |

## 3. 依赖顺序

```text
安全预检
  -> 密码学与服务单元测试
  -> Redis Lua / PostgreSQL Mapper 隔离集成测试
  -> Web 传输与前端契约测试
  -> 完整仓库回归
  -> 可选依赖审计
  -> 可选外部 Chrome 人工验收
```

高风险的密钥加密、令牌提前签发和 Redis 原子性验证放在前面。只有聚焦测试通过后才运行耗时更长、覆盖面更广的完整回归。

## 4. 分阶段任务

### Task 1：测试环境安全预检

**说明：** 确认当前工作区、Java/Node/Maven/Docker 可用性和测试配置。只读取状态，不启动服务。

**验收标准：**

- [ ] `application-test.yml` 继续排除真实 DataSource、Redis 和 RabbitMQ 自动配置。
- [ ] 没有任何测试命令引用生产主机、生产密码或用户已运行的 PostgreSQL/Redis 实例。
- [ ] Docker 不可用时，计划中的容器测试被标记为“跳过”，而不是改连本机端口。

**拟执行命令：**

```powershell
git status --short
mvn -version
node --version
npm --version
docker version
```

**依赖：** 无。

**预计范围：** XS，只读。

### Task 2：密码学和核心状态机聚焦测试

**说明：** 先运行不依赖外部基础设施的 TOTP 单元测试，验证 32 随机字节、52 字符无填充 Base32、RFC 6238、30 秒周期、六位码、前后一个时间片、AES-GCM 防篡改、用户 AAD 隔离、登录挑战和管理状态机。

**验收标准：**

- [ ] RFC 6238 标准向量和边界时间片通过。
- [ ] 错误用户、错误主密钥、被篡改密文都无法解密。
- [ ] 启用 TOTP 时第一因子只产生 `TOTP_REQUIRED`，不产生 AT、RT、CSRF。
- [ ] 待确认新密钥验证失败不写库；确认成功才执行 CAS；关闭同时置 `false` 和清空密文。

**拟执行命令：**

```powershell
mvn -pl ai-temperate-service -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=Rfc6238TotpCodeServiceImplTest,AesGcmTotpSecretProtectorImplTest,TotpLoginServiceImplTest,TotpManagementServiceImplTest,LoginCompletionServiceImplTest" test
```

**依赖：** Task 1。

**预计范围：** S；只执行现有聚焦测试，失败时先报告。

### Task 3：补齐 Redis Lua 原子状态集成测试

**说明：** 为 TOTP 新增三组 Redis Testcontainers 测试，不复用本机 Redis。验证登录挑战、待确认设置和 step-up proof 的 TTL、设备/动作绑定、覆盖、限次、一次性消费和防重放。

**验收标准：**

- [ ] `login-flow`：错误设备拒绝；第五次失败销毁；成功时挑战消费与 `used-step SET NX` 在一个 Lua 中完成。
- [ ] `setup`：同用户重新申请会覆盖旧 token；十分钟过期；第五次新码失败销毁；保存的旧状态快照可阻止陈旧 CAS。
- [ ] `step-up-proof`：绑定用户、设备和 ENABLE/ROTATE/DISABLE；错误动作拒绝；第五次当前 TOTP 失败销毁；成功只能消费一次。
- [ ] Redis 不可用和脚本返回异常时全部 Fail Closed。

**拟新增测试文件：**

- `ai-temperate-service/src/test/java/com/example/temperate/service/auth/totp/login/store/impl/RedisTotpLoginChallengeStoreIntegrationTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/auth/totp/management/store/impl/RedisTotpSetupStoreIntegrationTest.java`
- `ai-temperate-service/src/test/java/com/example/temperate/service/auth/totp/stepup/store/impl/RedisTotpStepUpStoreIntegrationTest.java`

**拟执行命令：**

```powershell
mvn -pl ai-temperate-service -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=RedisTotpLoginChallengeStoreIntegrationTest,RedisTotpSetupStoreIntegrationTest,RedisTotpStepUpStoreIntegrationTest" test
```

**依赖：** Task 2、Docker 可用。

**预计范围：** M，三个独立测试类。

### Checkpoint A：核心安全边界

- [ ] 密码学、服务状态机和 Redis 原子测试全部通过。
- [ ] 失败、错误、跳过项目已分开记录。
- [ ] 如发现生产代码缺陷，停止扩大测试范围，先提交证据和修复建议。

### Task 4：PostgreSQL Mapper 与数据库状态集成测试

**说明：** 扩展现有 PostgreSQL 15 Testcontainers Mapper 测试，直接从 CREATE 源文件建立隔离表，验证两个字段、查询最小化和 CAS 更新。

**验收标准：**

- [ ] `userloginidentity` 只有计划内的两个 TOTP 字段，无新 TOTP 表、索引或物理外键。
- [ ] 普通身份查询不加载 `totp_secret_encrypted`；专用凭据查询才读取密文。
- [ ] 开启/轮换只有旧状态和旧密文同时匹配时影响一行。
- [ ] 关闭以单条 SQL 同时设置 `totp_enabled=false` 和 `totp_secret_encrypted=NULL`。

**拟修改文件：**

- `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/identity/PostgreSqlMapperIntegrationTest.java`

**拟执行命令：**

```powershell
mvn -pl ai-temperate-mapper -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=AuthenticationMapperContractTest,PersistenceSqlContractTest,PostgreSqlMapperIntegrationTest" test
```

**依赖：** Task 1、Docker 可用。

**预计范围：** S，一个现有集成测试类和两个现有契约测试。

### Task 5：Web 令牌传输与管理接口契约测试

**说明：** 验证登录二次安全门、H5/Android 挑战传输、Cookie 属性、错误状态、`no-store` 以及当前用户管理接口不接收用户 ID。

**验收标准：**

- [ ] H5 的原始 TOTP flow token 只进入 `HttpOnly + Secure + SameSite=Strict`、路径隔离的 Cookie，不出现在 JSON。
- [ ] Android 只在响应体/请求头运输 flow token；验证完成前不返回 AT、RT、CSRF。
- [ ] 管理接口只从 `SessionPrincipal` 取用户，响应为 `private, no-store`。
- [ ] 过期、超限、状态冲突、配置不可用和 step-up 缺失映射到预期 HTTP 状态。

**拟新增或修改测试文件：**

- `ai-temperate-web/src/test/java/com/example/temperate/web/user/security/controller/CurrentUserTotpControllerTest.java`
- `ai-temperate-web/src/test/java/com/example/temperate/web/auth/login/controller/LoginControllerTokenTransportTest.java`
- `ai-temperate-web/src/test/java/com/example/temperate/web/auth/flow/transport/AuthFlowCookieWriterTest.java`
- `ai-temperate-web/src/test/java/com/example/temperate/web/auth/config/SecurityConfigurationTest.java`

**拟执行命令：**

```powershell
mvn -pl ai-temperate-web -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=CurrentUserTotpControllerTest,LoginControllerTokenTransportTest,AuthFlowCookieWriterTest,AuthCookieWriterTest,SecurityConfigurationTest" test
```

**依赖：** Checkpoint A、Task 4。

**预计范围：** M。

### Task 6：前端本地二维码与安全存储契约测试

**说明：** 验证后端只返回 Base32/`otpauth` 配置，二维码完全在前端本地生成；H5 不把原始 flow token 放进可读存储，Android 使用 KeyStore 状态。

**验收标准：**

- [ ] `qrcode-generator` 只读取 `otpauthUri` 并生成本地 SVG data URL，不调用远程二维码接口。
- [ ] H5 storage 只保留到期时间和剩余次数等非敏感元数据。
- [ ] Android TOTP 挑战进入现有 AndroidKeyStore AES-GCM 封装。
- [ ] `TOTP_REQUIRED` 不保存正式登录会话；`AUTHENTICATED` 后才保存。

**拟执行命令：**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:auth-totp
npm run test:auth
```

以上命令执行时应分别运行，不使用命令串联；执行结束后回到仓库根目录。

**依赖：** Task 5。

**预计范围：** S，现有 Node 契约测试。

### Checkpoint B：TOTP 功能回归

- [ ] Mapper、Web 和前端聚焦测试通过。
- [ ] 未连接任何生产或用户现有基础设施。
- [ ] 测试报告不包含 Base32 密钥、`otpauth` URI、验证码或真实 Token。

### Task 7：完整仓库回归

**说明：** 聚焦测试全部通过后，运行项目规定的完整 Java 验证，检查 TOTP 改动没有破坏其他认证、会话、注册和用户功能。

**验收标准：**

- [ ] Maven 全部 reactor 模块完成，失败/跳过数量有证据。
- [ ] Spring test profile 上下文可以绑定 TOTP 配置，不连接真实外部服务。
- [ ] Testcontainers 用例仅写临时容器；测试结束没有遗留运行中的测试容器。

**拟执行命令：**

```powershell
mvn clean verify
```

该命令会删除并重新创建各模块 `target/`，并可能拉取/启动仓库其他测试所需的 PostgreSQL、Redis、RabbitMQ 镜像。执行前需要再次确认 Docker 范围。

**依赖：** Checkpoint B。

**预计范围：** M，耗时取决于本机 Docker 镜像缓存。

### Task 8：依赖报告与可选联网漏洞审计

**说明：** 先生成 Maven 依赖树；针对 npm 自动提示的现有 25 个漏洞，只有在用户明确批准联网后才运行只读审计。不自动升级或修复依赖。

**拟执行命令：**

```powershell
mvn dependency:tree
```

可选联网命令：

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
npm audit --package-lock-only --audit-level=high
```

**验收标准：**

- [ ] 依赖报告区分 TOTP 新增直接依赖和仓库既有依赖。
- [ ] 审计结果只报告，不运行 `npm audit fix`，不改 package.json/package-lock.json。

**依赖：** Task 7；npm audit 另需用户批准联网。

**预计范围：** S。

### Task 9：可选外部 Chrome 人工端到端验收

**说明：** 自动化测试通过后，使用隔离 PostgreSQL/Redis 和测试用户，验证开启、重新登录、轮换与关闭。只允许连接用户外部 Chrome，并在操作前确认浏览器类型严格为 `extension`；不可用时立即停止，不回退到 Codex 内置浏览器。

**验收标准：**

- [ ] 未启用用户完成第一因子后直接登录。
- [ ] 开启流程显示前端本地二维码，确认前数据库保持未启用，正确新码确认后才启用并要求重新登录。
- [ ] 已启用用户第一因子完成后停在 TOTP 安全门，正确动态码后才得到会话。
- [ ] 轮换确认前旧密钥有效；新码确认后旧密钥失效。
- [ ] 关闭后数据库状态为 `false + NULL`，全部 Refresh Session 撤销并要求重新登录。

**写入范围：** 仅隔离容器中的测试用户和 Redis 临时键；不得使用真实邮箱、手机号、密码或生产密钥。

**依赖：** Task 7、外部 Chrome 扩展连接、用户确认测试数据和容器范围。

**预计范围：** M，需要用户参与扫描或输入测试认证器验证码。

## 5. 停止条件

出现以下任一情况立即停止对应测试并报告：

- 配置解析到非 Testcontainers 的 PostgreSQL、Redis、RabbitMQ 主机或端口。
- 需要真实短信、邮件、生产 Secret 或真实用户账号才能继续。
- Docker/外部 Chrome 不可用，而测试无法保持隔离。
- 测试输出出现完整 Base32 密钥、`otpauth` URI、验证码、Token、邮箱或手机号。
- 聚焦测试发现生产实现缺陷；在用户确认修复方案前不继续扩大回归范围。

## 6. 最终报告格式

第二阶段结果必须分别列出：

- 通过：具体命令、测试数量和关键安全断言。
- 失败：首次失败证据、根因范围和是否属于本次 TOTP 改动。
- 跳过：例如 Docker 不可用导致的 Testcontainers 跳过。
- 未执行：例如未获联网审计或外部 Chrome 授权。
- 外部状态：实际创建和清理了哪些临时容器、测试数据和构建产物。
