# RabbitMQ delayed 测试镜像

该镜像只用于 AI Generation 隔离集成测试，固定 RabbitMQ 4.1 与官方 delayed-message 插件 4.1.0。

```powershell
docker build -t ait-rabbitmq-delayed:4.1.0 docker/test/rabbitmq-delayed
$env:AIT_TEST_RABBIT_DELAYED_IMAGE = "ait-rabbitmq-delayed:4.1.0"
```

插件 SHA-256 固定为：

```text
567f876378e70af9d949de4066bbb2fc30162b46bcbc9efe2430076b172e4a87
```

禁止将该测试账号、容器或镜像配置用于生产 RabbitMQ。
