# JDK Service Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for root-agent inline execution. Project policy forbids subagent-driven development.

**Goal:** 让 Spring 事务 Service 使用 JDK 接口代理，避免 CGLIB 无法代理 `final` 实现类而导致应用上下文启动失败。

**Architecture:** 保留现有 Service 接口、`final` 实现类和事务边界，只在 Spring Boot 应用配置中关闭目标类代理。先增加生产 YAML 契约测试，再加入带环境变量覆盖能力的 `spring.aop.proxy-target-class=false` 配置。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AOP、JUnit 5、AssertJ、YAML。

---

### Task 1: 增加 JDK 接口代理配置契约

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/architecture/AuthenticationBusinessContractTest.java`

- [ ] **Step 1: 写入失败优先的配置契约测试**

在 `authSessionConfigurationIsExplicitInProductionAndTestYaml` 后新增：

```java
@Test
void productionUsesJdkInterfaceProxiesForFinalServiceImplementations() throws Exception {
    String production = Files.readString(PROJECT_ROOT.resolve(
            "ai-temperate-web/src/main/resources/application.yml"));

    assertThat(production).contains(
            "proxy-target-class: ${SPRING_AOP_PROXY_TARGET_CLASS:false}");
}
```

该断言在生产 YAML 尚未配置 JDK 代理时失败，并保护 `final` Service 与事务代理的兼容约束。

- [ ] **Step 2: 第一阶段不运行 RED 测试**

根据项目 `AGENTS.md` 第 16 节，本阶段不执行测试。第二阶段如经用户明确批准，运行：

```powershell
mvn -pl ai-temperate-service -am -Dtest=AuthenticationBusinessContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

修改前预期结果：新增断言失败，因为生产 YAML 不包含 `proxy-target-class`。

### Task 2: 配置 Spring 使用 JDK 接口代理

**Files:**
- Modify: `ai-temperate-web/src/main/resources/application.yml`

- [ ] **Step 1: 在 `spring` 根节点下加入 AOP 配置**

紧接应用名称配置组之后加入，每个实际 YAML 行都有紧邻中文注释：

```yaml
  # Spring AOP 代理方式用于兼容不可继承的 final Service 实现类。
  aop:
    # 事务 Service 统一通过业务接口调用，默认使用 JDK 接口代理并允许环境变量覆盖。
    proxy-target-class: ${SPRING_AOP_PROXY_TARGET_CLASS:false}
```

不删除任何 Service 的 `final`，不修改接口、事务注解或调用方。

- [ ] **Step 2: 第一阶段不运行 GREEN 或上下文测试**

根据项目规则，本阶段不执行测试、编译或应用启动。第二阶段如经用户明确批准，先运行契约测试：

```powershell
mvn -pl ai-temperate-service -am -Dtest=AuthenticationBusinessContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期结果：`AuthenticationBusinessContractTest` 通过。

随后运行 Spring 上下文测试：

```powershell
mvn -pl ai-temperate-web -am -Dtest=AiTemperateApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期结果：应用测试上下文成功创建，事务 Service 不再触发 CGLIB `final` 类代理错误。测试可能使用隔离的测试容器和测试配置，不应连接生产基础设施。

### Task 3: 静态交付检查

**Files:**
- Verify: `ai-temperate-service/src/test/java/com/example/temperate/service/auth/architecture/AuthenticationBusinessContractTest.java`
- Verify: `ai-temperate-web/src/main/resources/application.yml`

- [ ] **Step 1: 检查修改范围和 YAML 注释邻接**

只读检查新增断言、配置键、中文父节点注释和中文叶子注释；确认没有修改密钥、Service 接口、实现类或事务逻辑。

- [ ] **Step 2: 交付第一阶段结果**

报告已修改文件，并明确说明测试、编译、打包和应用启动均未执行。当前目录不是 Git 工作树，因此不执行提交。

