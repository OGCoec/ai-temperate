package com.example.temperate.web.registration.config;

import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryRabbitNames;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置验证码投递使用的 RabbitMQ 延迟交换机、终态交换机、Quorum 队列、动态 mandatory、JSON 转换器和手动 ACK 消费容器。
 *
 * <p>终态队列只保存受保护载荷与安全失败分类；消费者必须等待终态发布确认后才更新 Redis 并 ACK 原消息。</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "app.registration.delivery.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VerificationDeliveryRabbitConfiguration {

    @Bean
    CustomExchange verificationDeliveryExchange() {
        return new CustomExchange(
                VerificationDeliveryRabbitNames.EXCHANGE,
                "x-delayed-message",
                true,
                false,
                Map.of("x-delayed-type", "direct"));
    }

    @Bean
    DirectExchange verificationDeliveryTerminalExchange() {
        return new DirectExchange(
                VerificationDeliveryRabbitNames.TERMINAL_EXCHANGE, true, false);
    }

    @Bean
    Queue verificationDeliveryEmailQueue() {
        return QueueBuilder.durable(VerificationDeliveryRabbitNames.EMAIL_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Queue verificationDeliverySmsQueue() {
        return QueueBuilder.durable(VerificationDeliveryRabbitNames.SMS_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Queue verificationDeliveryTerminalQueue() {
        return QueueBuilder.durable(VerificationDeliveryRabbitNames.TERMINAL_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Binding verificationDeliveryEmailBinding(
            Queue verificationDeliveryEmailQueue,
            CustomExchange verificationDeliveryExchange) {
        return BindingBuilder.bind(verificationDeliveryEmailQueue)
                .to(verificationDeliveryExchange)
                .with(VerificationDeliveryRabbitNames.EMAIL_ROUTING_KEY)
                .noargs();
    }

    @Bean
    Binding verificationDeliverySmsBinding(
            Queue verificationDeliverySmsQueue,
            CustomExchange verificationDeliveryExchange) {
        return BindingBuilder.bind(verificationDeliverySmsQueue)
                .to(verificationDeliveryExchange)
                .with(VerificationDeliveryRabbitNames.SMS_ROUTING_KEY)
                .noargs();
    }

    @Bean
    Binding verificationDeliveryTerminalBinding(
            Queue verificationDeliveryTerminalQueue,
            DirectExchange verificationDeliveryTerminalExchange) {
        return BindingBuilder.bind(verificationDeliveryTerminalQueue)
                .to(verificationDeliveryTerminalExchange)
                .with(VerificationDeliveryRabbitNames.TERMINAL_ROUTING_KEY);
    }

    @Bean("verificationDeliveryMessageConverter")
    MessageConverter verificationDeliveryMessageConverter() {
        return new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build());
    }

    @Bean
    RabbitTemplateCustomizer verificationDeliveryRabbitTemplateCustomizer(
            @Qualifier("verificationDeliveryMessageConverter")
                    MessageConverter messageConverter) {
        return rabbitTemplate -> {
            rabbitTemplate.setMessageConverter(messageConverter);
            // delayed-message 插件无法为未来路由提供可靠 Return 语义，只有不带延迟头的即时消息启用 mandatory。
            rabbitTemplate.setMandatoryExpressionString(
                    "messageProperties.headers['x-delay'] == null");
        };
    }

    @Bean("verificationDeliveryEmailListenerContainerFactory")
    SimpleRabbitListenerContainerFactory verificationDeliveryEmailListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("verificationDeliveryMessageConverter")
                    MessageConverter messageConverter,
            @Value("${app.registration.delivery.rabbit.email-consumers:1}")
                    int emailConsumers) {
        return listenerFactory(configurer, connectionFactory, messageConverter, emailConsumers);
    }

    @Bean("verificationDeliverySmsListenerContainerFactory")
    SimpleRabbitListenerContainerFactory verificationDeliverySmsListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("verificationDeliveryMessageConverter")
                    MessageConverter messageConverter,
            @Value("${app.registration.delivery.rabbit.sms-consumers:1}")
                    int smsConsumers) {
        return listenerFactory(configurer, connectionFactory, messageConverter, smsConsumers);
    }

    private static SimpleRabbitListenerContainerFactory listenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            int consumers) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        int boundedConsumers = Math.max(1, consumers);
        factory.setConcurrentConsumers(boundedConsumers);
        factory.setMaxConcurrentConsumers(boundedConsumers);
        return factory;
    }
}
