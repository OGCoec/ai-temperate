package com.example.temperate.web.registration.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryRabbitNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 验证验证码 RabbitTemplate 按消息是否带有延迟头选择 mandatory 语义。
 *
 * <p>普通消息必须保留无法路由检测；使用 delayed-message 插件的消息关闭 mandatory，避免把插件的
 * 非可靠 Return 误判为 Broker 未保存延迟消息。测试不连接 RabbitMQ。</p>
 */
class VerificationDeliveryRabbitConfigurationTest {

    @Test
    void enablesMandatoryForImmediateMessage() {
        RabbitTemplate rabbitTemplate = customizedTemplate();
        Message immediate = new Message(new byte[0], new MessageProperties());

        assertThat(rabbitTemplate.isMandatoryFor(immediate)).isTrue();
    }

    @Test
    void disablesMandatoryForDelayedMessage() {
        RabbitTemplate rabbitTemplate = customizedTemplate();
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-delay", 10_000L);
        Message delayed = new Message(new byte[0], properties);

        assertThat(rabbitTemplate.isMandatoryFor(delayed)).isFalse();
    }

    @Test
    void terminalTopologyIsDurableQuorumAndDirectlyBound() {
        VerificationDeliveryRabbitConfiguration configuration =
                new VerificationDeliveryRabbitConfiguration();
        DirectExchange exchange = configuration.verificationDeliveryTerminalExchange();
        Queue queue = configuration.verificationDeliveryTerminalQueue();
        Binding binding = configuration.verificationDeliveryTerminalBinding(queue, exchange);

        assertThat(exchange.getName())
                .isEqualTo(VerificationDeliveryRabbitNames.TERMINAL_EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(queue.getName())
                .isEqualTo(VerificationDeliveryRabbitNames.TERMINAL_QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(binding.getRoutingKey())
                .isEqualTo(VerificationDeliveryRabbitNames.TERMINAL_ROUTING_KEY);
    }

    private static RabbitTemplate customizedTemplate() {
        VerificationDeliveryRabbitConfiguration configuration =
                new VerificationDeliveryRabbitConfiguration();
        RabbitTemplate rabbitTemplate = new RabbitTemplate();
        configuration.verificationDeliveryRabbitTemplateCustomizer(
                        configuration.verificationDeliveryMessageConverter())
                .customize(rabbitTemplate);
        return rabbitTemplate;
    }
}
