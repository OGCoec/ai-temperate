package com.example.temperate.web.user.membership.payment.config;

import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类是来声明会员支付和软关闭的持久延时交换机、Quorum 业务队列、独立 DLQ、Confirm 模板及手动 ACK 容器。
 *
 * <p>两条业务队列使用独立拓扑，每条队列固定三十二个消费者且 prefetch 为 20；监听失败由 Quorum Queue
 * 的三次 delivery limit 有限重投，耗尽后进入独立 DLQ，禁止异常消息无限循环。</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public class MembershipPaymentRabbitConfiguration {

    @Bean
    Declarables membershipPaymentRabbitTopology() {
        CustomExchange paymentExchange = delayedExchange(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE);
        DirectExchange paymentDeadExchange = new DirectExchange(
                MembershipPaymentRabbitNames.PAYMENT_DLX, true, false);
        Queue paymentQueue = businessQueue(
                MembershipPaymentRabbitNames.PAYMENT_QUEUE,
                MembershipPaymentRabbitNames.PAYMENT_DLX,
                MembershipPaymentRabbitNames.PAYMENT_DLQ_ROUTING_KEY);
        Queue paymentDeadQueue = QueueBuilder
                .durable(MembershipPaymentRabbitNames.PAYMENT_DLQ)
                .quorum()
                .build();

        CustomExchange closingExchange = delayedExchange(
                MembershipPaymentRabbitNames.CLOSING_EXCHANGE);
        DirectExchange closingDeadExchange = new DirectExchange(
                MembershipPaymentRabbitNames.CLOSING_DLX, true, false);
        Queue closingQueue = businessQueue(
                MembershipPaymentRabbitNames.CLOSING_QUEUE,
                MembershipPaymentRabbitNames.CLOSING_DLX,
                MembershipPaymentRabbitNames.CLOSING_DLQ_ROUTING_KEY);
        Queue closingDeadQueue = QueueBuilder
                .durable(MembershipPaymentRabbitNames.CLOSING_DLQ)
                .quorum()
                .build();

        List<Declarable> topology = List.of(
                paymentExchange,
                paymentDeadExchange,
                paymentQueue,
                paymentDeadQueue,
                BindingBuilder.bind(paymentQueue)
                        .to(paymentExchange)
                        .with(MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY)
                        .noargs(),
                BindingBuilder.bind(paymentDeadQueue)
                        .to(paymentDeadExchange)
                        .with(MembershipPaymentRabbitNames.PAYMENT_DLQ_ROUTING_KEY),
                closingExchange,
                closingDeadExchange,
                closingQueue,
                closingDeadQueue,
                BindingBuilder.bind(closingQueue)
                        .to(closingExchange)
                        .with(MembershipPaymentRabbitNames.CLOSING_ROUTING_KEY)
                        .noargs(),
                BindingBuilder.bind(closingDeadQueue)
                        .to(closingDeadExchange)
                        .with(MembershipPaymentRabbitNames.CLOSING_DLQ_ROUTING_KEY));
        return new Declarables(topology);
    }

    @Bean("membershipPaymentMessageConverter")
    MessageConverter membershipPaymentMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 专用模板只对即时消息启用 mandatory；x-delayed-message 插件会在未来路由前产生伪 Return，
     * 因而延迟消息以拓扑声明和 Confirm ACK 作为投递接受依据，避免把已进入延迟交换机的消息误判为失败。
     */
    @Bean("membershipPaymentRabbitTemplate")
    RabbitTemplate membershipPaymentRabbitTemplate(
            ConnectionFactory connectionFactory,
            @Qualifier("membershipPaymentMessageConverter")
                    MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // delayed-message 插件无法为未来路由提供可靠 Return 语义；即时消息仍保留 mandatory 检查。
        template.setMandatoryExpressionString(
                "messageProperties.headers['x-delay'] == null");
        return template;
    }

    @Bean("membershipPaymentListenerContainerFactory")
    SimpleRabbitListenerContainerFactory membershipPaymentListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("membershipPaymentMessageConverter")
                    MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(20);
        // 两条队列各自使用固定四十八个消费者，提高同点到期消息的处理时效，并避免自动扩缩容引入不可复现抖动。
        factory.setConcurrentConsumers(48);
        factory.setMaxConcurrentConsumers(48);
        return factory;
    }

    private static CustomExchange delayedExchange(String name) {
        return new CustomExchange(
                name,
                "x-delayed-message",
                true,
                false,
                Map.of("x-delayed-type", "direct"));
    }

    private static Queue businessQueue(
            String name,
            String deadLetterExchange,
            String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-delivery-limit", 3)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }
}
