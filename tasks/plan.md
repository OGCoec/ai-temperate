# 已实施方案：Spring Boot 3.5.5 精简多模块项目

## 模块依赖

```text
ai-temperate-web
  -> ai-temperate-service
    -> ai-temperate-mapper
      -> ai-temperate-model
        -> ai-temperate-common
```

- `common`：Redis Starter、Redisson、Hutool、JJWT、libphonenumber、IP2Location。
- `model`：common、Lombok。
- `mapper`：model、MyBatis、PageHelper、PostgreSQL JDBC。
- `service`：common、mapper、AMQP、阿里云号码认证、阿里云 OSS、微信支付。
- `web`：service、Web、Validation、Security、Springdoc、Knife4j、测试依赖。

Redis、AMQP 和 PostgreSQL 不写显式版本，由 Spring Boot 3.5.5 管理。

## 基础设施配置

- PostgreSQL：单主库 `127.0.0.1:5431/ai_temperate`。
- Redis：`127.0.0.1:6378`，database 0。
- RabbitMQ AMQP：`127.0.0.1:5673`。
- RabbitMQ Management：`http://127.0.0.1:11111`，不写入 `spring.rabbitmq`。
- 不包含分库、分表、从库、读写分离或路由数据源配置。

## 验证结果

- `mvn clean verify`：通过，6 个 reactor 模块全部成功。
- 测试：2 个，失败 0，错误 0。
- 可执行 JAR：已生成并用 test profile 启动验证。
- 健康接口：HTTP 200。
- Redis/AMQP/PostgreSQL 直接依赖归属符合模块规划。
- RabbitMQ AMQP 默认配置已更新为 5673；该端口仍需结合实际 Broker 做连接验证。
