package com.example.temperate.web.rabbitmq.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;

/**
 * 验证 RabbitTemplate 发布前统一设置持久化投递模式的测试。
 */
class RabbitDurabilityConfigurationTest {

    @Test
    void forcesEveryPublishedMessageToBePersistent() {
        RabbitTemplate rabbitTemplate = new RabbitTemplate();
        RabbitTemplateCustomizer customizer =
                new RabbitDurabilityConfiguration().persistentRabbitMessageCustomizer();
        customizer.customize(rabbitTemplate);

        Message message = new Message(new byte[0]);
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);

        Collection<MessagePostProcessor> processors = rabbitTemplate.getBeforePublishPostProcessors();
        Message processed = processors.iterator().next().postProcessMessage(message);

        assertThat(processed.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
    }
}
