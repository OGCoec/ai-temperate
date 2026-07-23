# 实施清单

- [x] 创建 Spring Boot 3.5.5 父工程。
- [x] 创建 common、model、mapper、service、web 五个模块。
- [x] 父 POM 统一管理第三方版本和内部模块版本。
- [x] common 直接声明 Redis Starter。
- [x] mapper 直接声明 PostgreSQL JDBC。
- [x] service 直接声明 AMQP Starter。
- [x] 创建单 PostgreSQL、单 Redis、单 RabbitMQ 配置。
- [x] 删除分库、分表、从库和读写路由设计。
- [x] 创建启动类、安全配置和健康接口。
- [x] 创建不依赖外部服务的 test profile。
- [x] 运行 `mvn clean verify`。
- [x] 检查 Maven 依赖树和直接依赖归属。
- [x] 启动可执行 JAR 并验证健康接口。
- [x] 验证 RabbitMQ Management 页面响应。
- [ ] PostgreSQL 5431 真实连接测试：当前端口未监听。
- [ ] Redis 6378 真实连接测试：当前端口未监听。
- [ ] RabbitMQ 5673 真实连接测试：需要实际 Broker 和账号。
- [ ] RabbitMQ Management API 登录测试：当前 `guest/guest` 返回 401。
