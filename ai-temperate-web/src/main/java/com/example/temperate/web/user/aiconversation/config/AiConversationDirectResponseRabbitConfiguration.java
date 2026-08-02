package com.example.temperate.web.user.aiconversation.config;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseRabbitNames;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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
 * 声明直接 MVC SSE 在多实例部署中使用的持久 Stop 控制拓扑，该拓扑不承载正文且不依赖后台 Generation 开关。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.ai-conversation.direct-response-cancellation",
        name = "rabbit-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AiConversationDirectResponseRabbitConfiguration {

    @Bean
    DirectExchange aiConversationDirectResponseControlExchange() {
        return new DirectExchange(
                AiConversationDirectResponseRabbitNames.CONTROL_EXCHANGE,
                true,
                false);
    }

    @Bean
    DirectExchange aiConversationDirectResponseDeadLetterExchange() {
        return new DirectExchange(
                AiConversationDirectResponseRabbitNames.DEAD_LETTER_EXCHANGE,
                true,
                false);
    }

    @Bean
    Queue aiConversationDirectResponseControlQueue(
            AiConversationAsyncGenerationProperties properties) {
        return QueueBuilder.durable(
                        AiConversationDirectResponseRabbitNames.controlQueue(
                                properties.instanceId()))
                .quorum()
                .deadLetterExchange(
                        AiConversationDirectResponseRabbitNames
                                .DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(
                        AiConversationDirectResponseRabbitNames
                                .DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue aiConversationDirectResponseDeadLetterQueue() {
        return QueueBuilder.durable(
                        AiConversationDirectResponseRabbitNames
                                .DEAD_LETTER_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Binding aiConversationDirectResponseControlBinding(
            @Qualifier("aiConversationDirectResponseControlQueue")
                    Queue queue,
            @Qualifier("aiConversationDirectResponseControlExchange")
                    DirectExchange exchange,
            AiConversationAsyncGenerationProperties properties) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(AiConversationDirectResponseRabbitNames.controlRoutingKey(
                        properties.instanceId()));
    }

    @Bean
    Binding aiConversationDirectResponseDeadLetterBinding(
            @Qualifier("aiConversationDirectResponseDeadLetterQueue")
                    Queue queue,
            @Qualifier("aiConversationDirectResponseDeadLetterExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(AiConversationDirectResponseRabbitNames
                        .DEAD_LETTER_ROUTING_KEY);
    }

    @Bean("aiConversationDirectResponseMessageConverter")
    MessageConverter aiConversationDirectResponseMessageConverter() {
        return new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build());
    }

    @Bean("aiConversationDirectResponseRabbitTemplate")
    RabbitTemplate aiConversationDirectResponseRabbitTemplate(
            ConnectionFactory connectionFactory,
            @Qualifier("aiConversationDirectResponseMessageConverter")
                    MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean("aiConversationDirectResponseControlListenerFactory")
    SimpleRabbitListenerContainerFactory
            aiConversationDirectResponseControlListenerFactory(
                    SimpleRabbitListenerContainerFactoryConfigurer configurer,
                    ConnectionFactory connectionFactory,
                    @Qualifier("aiConversationDirectResponseMessageConverter")
                            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }
}
