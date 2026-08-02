# syntax=docker/dockerfile:1.7

# 测试 Broker 与项目当前 RabbitMQ 4.1 协议和延迟插件版本保持一致。
FROM rabbitmq:4.1-management

# 插件只从 RabbitMQ 官方归档发布页下载，并由 BuildKit 在写入镜像前校验固定摘要。
ADD --checksum=sha256:567f876378e70af9d949de4066bbb2fc30162b46bcbc9efe2430076b172e4a87 \
    https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v4.1.0/rabbitmq_delayed_message_exchange-4.1.0.ez \
    /plugins/rabbitmq_delayed_message_exchange-4.1.0.ez

# 远程 ADD 默认权限只允许 root 读取，必须先开放插件读取权限再由 RabbitMQ 用户加载。
RUN chmod 0644 /plugins/rabbitmq_delayed_message_exchange-4.1.0.ez \
    && rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
