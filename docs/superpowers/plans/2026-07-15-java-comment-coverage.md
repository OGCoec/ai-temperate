# Java 中文注释全量覆盖 Implementation Plan

> **执行约束：** 本计划必须由根代理在当前工作区内联执行；禁止分派子代理、禁止修改业务逻辑、禁止运行编译或测试。

**Goal:** 为仓库内全部 252 个非自动生成 Java 源文件补齐中文用途 JavaDoc，并为实际包含复杂业务、并发/幂等、事务、安全或边界转换机制的生产实现补齐原理注释。

**Architecture:** 注释采用两层结构：每个主要顶级类型均有用途 JavaDoc；只有存在非直观机制的文件才添加类级、方法级或局部原理说明。所有改动只能新增或调整注释，不得改变可执行代码、导入、配置、测试断言或公共 API。

**Tech Stack:** Java、Spring、Redis/Lua、PostgreSQL、JUnit；仅修改 Markdown 与 Java 注释。

---

## 统一执行规则

- 所有 `src/main/java/**/*.java` 与 `src/test/java/**/*.java` 文件均在范围内。
- 每次 `apply_patch` 处理 3 至 5 个文件，避免大范围注释误置。
- 每个文件在主要顶级类型前放置中文 JavaDoc。第一句说明文件用途；随后只在必要时说明输入、输出、协作边界或不负责的内容。
- 复杂机制文件必须以实际代码为准补充：业务编排、事务、原子性、并发竞态、幂等、安全校验顺序或边界转换；简单文件不虚构此类注释。
- 禁止注释中出现明文凭据、Token、验证码、完整 PII 或安全绕过细节。
- 每批完成后只运行 `rg`/PowerShell 静态检查；根据项目第一阶段规则，不运行 Maven、测试、编译、打包或外部服务连接。

### Task 1: 固化全量适用的规范

**Files:**
- Modify: `AGENTS.md`

- [ ] 将“新增或修改的 Java 源文件”明确为“现有及未来全部非自动生成 Java 源文件”。
- [ ] 将 Code Review 检查项同步改为全量存量与新增文件都必须检查用途说明。
- [ ] 静态确认规范同时规定用途 JavaDoc 与复杂机制原理注释的分层要求。

### Task 2: common、mapper 与 model 模块用途说明

**Files:**
- Modify: `ai-temperate-common/src/main/java/**/*.java`
- Modify: `ai-temperate-common/src/test/java/**/*.java`
- Modify: `ai-temperate-mapper/src/main/java/**/*.java`
- Modify: `ai-temperate-mapper/src/test/java/**/*.java`
- Modify: `ai-temperate-model/src/main/java/**/*.java`
- Modify: `ai-temperate-model/src/test/java/**/*.java`

- [ ] 逐文件补充主要类型用途 JavaDoc。
- [ ] 为计数布隆过滤器、Snowflake ID、Redis Key/Value 编解码等实际存在的原子性、边界或并发实现补充原理注释。
- [ ] 对 DTO、枚举、Mapper、实体和测试类保持一到两句准确说明，不添加伪业务流程。
- [ ] 用静态扫描确认该三模块的每个 Java 文件都存在主要类型 JavaDoc。

### Task 3: service 认证与会话链路

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/auth/**/*.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/**/*.java`

- [ ] 为登录、令牌、会话、刷新、找回密码、风控和国家识别相关类型补齐用途 JavaDoc。
- [ ] 对 Redis/Lua 原子操作、刷新会话绑定、CSRF、密码重置事务、登录失败限流、验证码流程及防重放逻辑补充实际原理说明。
- [ ] 为认证测试类说明被验证的安全契约或失败边界。
- [ ] 静态检查认证包的用途说明覆盖与复杂机制注释位置。

### Task 4: service 注册、验证与通用业务链路

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/registration/**/*.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/registration/**/*.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/**/*.java`（排除 `auth` 与 `registration`）
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/**/*.java`（排除 `auth` 与 `registration`）

- [ ] 为注册、验证码投递、Turnstile、缓存、用户身份和其他服务类型补齐用途 JavaDoc。
- [ ] 对注册领取/释放、提交后清理、Redis 状态机、投递补偿、限流及事务后动作补充原理注释。
- [ ] 对普通接口、异常、DTO、枚举和测试保持简洁用途说明。
- [ ] 静态检查 service 全部 Java 文件的用途 JavaDoc 覆盖。

### Task 5: web 模块与 API 边界

**Files:**
- Modify: `ai-temperate-web/src/main/java/**/*.java`
- Modify: `ai-temperate-web/src/test/java/**/*.java`

- [ ] 为 Controller、Security 配置、过滤器、Cookie 写入器、异常处理、DTO、转换器和测试类补齐用途 JavaDoc。
- [ ] 对平台令牌来源隔离、CSRF、Cookie 属性、认证过滤链、请求边界转换和错误映射补充原理注释。
- [ ] 不修改路由、响应结构、Spring 注解参数或安全配置值。
- [ ] 静态检查 web 模块用途说明覆盖。

### Task 6: 全仓复核与交付

**Files:**
- Review: `AGENTS.md`
- Review: `**/src/main/java/**/*.java`
- Review: `**/src/test/java/**/*.java`

- [ ] 统计全部 Java 文件数、主要类型用途 JavaDoc 覆盖数和未覆盖文件清单。
- [ ] 复核复杂机制候选文件是否存在准确的中文原理说明，避免将模板注释误用于简单类型。
- [ ] 扫描新增注释，确认不含明文敏感数据、占位符或与代码矛盾的描述。
- [ ] 报告静态检查结果；明确未执行任何编译、测试或外部验证。

## 风险与缓解

| 风险 | 缓解方式 |
| --- | --- |
| 注释覆盖到错误的类型或遗漏嵌套主要类型 | 每批后使用文件清单与类型声明静态扫描复核。 |
| 用途说明流于类名翻译 | 每条 JavaDoc 至少包含职责、解决的问题或协作边界之一。 |
| 复杂机制注释与代码不一致 | 在修改前阅读目标方法和相关 Lua/事务调用，仅描述可观察到的顺序与不变量。 |
| 全量改动混入行为变化 | 每个补丁只添加注释；静态复核时检查无方法体、导入、注解参数和配置变更。 |

## 验收方式

仅执行只读静态检查：枚举纳入范围的 Java 文件、检测主要类型 JavaDoc、检查复杂机制候选文件与扫描敏感信息。依据项目第一阶段规则，不执行 Maven、JUnit、编译、打包、依赖分析或任何外部服务测试。
