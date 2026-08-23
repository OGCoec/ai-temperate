# 会员支付压测 Tomcat 4096 上限设计

## 目标

仅在 `loadtest-realtime` Profile 中，把 Tomcat 的连接接收队列、最大连接数和最大工作线程数的默认上限统一为 4096，满足 40 万账号 JMeter 压测的统一并发上限要求。

## 配置变更

修改 `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml`：

```yaml
server.tomcat.accept-count: 4096
server.tomcat.max-connections: 4096
server.tomcat.threads.max: 4096
```

三个配置继续保留现有环境变量覆盖能力：

```text
MEMBERSHIP_LOADTEST_TOMCAT_ACCEPT_COUNT
MEMBERSHIP_LOADTEST_TOMCAT_MAX_CONNECTIONS
MEMBERSHIP_LOADTEST_TOMCAT_MAX_THREADS
```

## 边界

- 不修改普通、测试或生产 Profile。
- 不修改 PostgreSQL `max_connections`、HikariCP、Redis 或 RabbitMQ 连接配置。
- 不修改 JMeter 当前的并发参数；JMeter 并发调整属于 40 万边界套件的后续实施。
- 只改变默认上限，现有 `6655` Java 进程必须重启后才会加载新值。

## 风险和运行约束

- `max-connections=4096` 表示连接容量，不代表 4096 个请求一定能同时完成。
- `max-threads=4096` 在当前 16 逻辑处理器机器上可能造成线程栈内存、上下文切换和调度延迟显著增加。
- 正式运行必须由 Codex 持续监控 JVM 内存、线程数、CPU、Tomcat 活跃线程、HTTP 超时和数据库连接等待。
- 若出现资源耗尽或吞吐下降，测试必须暂停并保留证据，不得把容量异常误判为业务逻辑失败。

## 验收

- YAML 三个默认值均为 4096，且每个配置行前保留准确的中文注释。
- 环境变量名称保持不变。
- 变更范围只包含 `application-loadtest-realtime.yml` 对应三项及注释。
- 第一阶段不自动运行测试、编译或重启应用；后续经用户批准后再执行配置解析和启动验证。
