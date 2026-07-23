# Service 事务代理兼容 final 实现类设计

## 问题

Spring Boot 当前默认使用 CGLIB 类代理。`LoginServiceImpl` 和
`PasswordResetServiceImpl` 是 `final` 类，CGLIB 无法通过继承生成事务代理，导致应用上下文启动失败。

## 决策

在应用级 Spring AOP 配置中将 `spring.aop.proxy-target-class` 固定为 `false`，统一使用 JDK
接口代理。现有事务 Service 均实现业务接口，生产代码也只按接口注入，因此不需要修改 Service
接口、实现类或业务调用方。

## 备选方案

- 取消事务 Service 实现类的 `final`：能够继续使用 CGLIB，但会放宽实现类继承边界，并可能在新
  `final` Service 出现时再次触发同类故障。
- 仅通过启动参数覆盖代理方式：可以临时启动，但配置没有固化，其他运行环境容易复发。

## 配置与兼容性

在 `application.yml` 的 `spring` 节点下新增带中文紧邻注释的 `aop` 配置，并允许通过
`SPRING_AOP_PROXY_TARGET_CLASS` 环境变量覆盖，默认值为 `false`。JDK 代理暴露 Service 接口，
不暴露实现类类型，符合项目禁止调用方注入具体 `Impl` 的约束。

## 回归保护

先在现有认证架构契约测试中增加断言，要求生产配置明确包含 JDK 接口代理设置；再修改生产 YAML。
依据项目两阶段交付规则，本阶段只写入测试和配置，不运行测试、编译或应用启动。

