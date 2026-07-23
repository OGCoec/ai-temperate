package com.example.temperate.web.rabbitmq.config;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 生产消息持久化模式的统一配置。
 *
 * <p>用途：在发布前为所有经 RabbitTemplate 发送的消息标记 {@link MessageDeliveryMode#PERSISTENT}。</p>
 *
 * <p>可靠性边界：持久化模式只是可靠投递链路的一环；Exchange/Queue durable、Publisher Confirm、消费者幂等与
 * 有限重试仍须由各自配置和业务实现保证。</p>
 */
@Configuration
public class RabbitDurabilityConfiguration {

    @Bean
    RabbitTemplateCustomizer persistentRabbitMessageCustomizer() {
        return rabbitTemplate -> rabbitTemplate.addBeforePublishPostProcessors(message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
    }
}
