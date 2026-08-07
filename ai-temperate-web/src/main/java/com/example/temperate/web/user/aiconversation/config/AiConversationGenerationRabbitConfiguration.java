package com.example.temperate.web.user.aiconversation.config;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRabbitNames;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明 AI 后台生成、Owner 控制、30 秒延迟检查、唯一终态和死信使用的 Durable Quorum RabbitMQ 拓扑。
 *
 * <p>所有监听器复用现有唯一 ConnectionFactory，并在 PostgreSQL 本地事务和后续发布确认成功后手动 ACK。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiConversationAsyncGenerationProperties.class)
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public class AiConversationGenerationRabbitConfiguration {

    @Bean
    DirectExchange aiConversationGenerationExchange() {
        return new DirectExchange(
                AiConversationGenerationRabbitNames.GENERATION_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange aiConversationGenerationControlExchange() {
        return new DirectExchange(
                AiConversationGenerationRabbitNames.CONTROL_EXCHANGE, true, false);
    }

    @Bean
    CustomExchange aiConversationGenerationDetachExchange() {
        return new CustomExchange(
                AiConversationGenerationRabbitNames.DETACH_EXCHANGE,
                "x-delayed-message",
                true,
                false,
                Map.of("x-delayed-type", "direct"));
    }

    @Bean
    DirectExchange aiConversationGenerationTerminalExchange() {
        return new DirectExchange(
                AiConversationGenerationRabbitNames.TERMINAL_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange aiConversationGenerationDeadLetterExchange() {
        return new DirectExchange(
                AiConversationGenerationRabbitNames.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue aiConversationGenerationQueue() {
        return reliableQueue(AiConversationGenerationRabbitNames.GENERATION_QUEUE);
    }

    @Bean
    Queue aiConversationGenerationWorkerV2Queue(
            AiConversationAsyncGenerationProperties properties) {
        return reliableQueue(AiConversationGenerationRabbitNames.workerQueueV2(
                properties.instanceId()));
    }

    @Bean
    Queue aiConversationGenerationControlQueue(
            AiConversationAsyncGenerationProperties properties) {
        return reliableQueue(AiConversationGenerationRabbitNames.controlQueue(
                properties.instanceId()));
    }

    @Bean
    Queue aiConversationGenerationDetachQueue() {
        return reliableQueue(AiConversationGenerationRabbitNames.DETACH_QUEUE);
    }

    @Bean
    Queue aiConversationGenerationTerminalQueue() {
        return reliableQueue(AiConversationGenerationRabbitNames.TERMINAL_QUEUE);
    }

    @Bean
    Queue aiConversationGenerationTerminalV2Queue() {
        return reliableQueue(AiConversationGenerationRabbitNames.TERMINAL_QUEUE_V2);
    }

    @Bean
    Queue aiConversationGenerationDeadLetterQueue() {
        return QueueBuilder.durable(AiConversationGenerationRabbitNames.DEAD_LETTER_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Binding aiConversationGenerationBinding(
            @Qualifier("aiConversationGenerationQueue") Queue aiConversationGenerationQueue,
            @Qualifier("aiConversationGenerationExchange")
                    DirectExchange aiConversationGenerationExchange) {
        return BindingBuilder.bind(aiConversationGenerationQueue)
                .to(aiConversationGenerationExchange)
                .with(AiConversationGenerationRabbitNames.GENERATION_ROUTING_KEY);
    }

    @Bean
    Binding aiConversationGenerationWorkerV2Binding(
            @Qualifier("aiConversationGenerationWorkerV2Queue")
                    Queue workerQueue,
            @Qualifier("aiConversationGenerationExchange")
                    DirectExchange generationExchange,
            AiConversationAsyncGenerationProperties properties) {
        return BindingBuilder.bind(workerQueue)
                .to(generationExchange)
                .with(AiConversationGenerationRabbitNames.workerRoutingKeyV2(
                        properties.instanceId()));
    }

    @Bean
    Binding aiConversationGenerationControlBinding(
            @Qualifier("aiConversationGenerationControlQueue")
                    Queue aiConversationGenerationControlQueue,
            @Qualifier("aiConversationGenerationControlExchange")
                    DirectExchange aiConversationGenerationControlExchange,
            AiConversationAsyncGenerationProperties properties) {
        return BindingBuilder.bind(aiConversationGenerationControlQueue)
                .to(aiConversationGenerationControlExchange)
                .with(AiConversationGenerationRabbitNames.controlRoutingKey(
                        properties.instanceId()));
    }

    @Bean
    Binding aiConversationGenerationDetachBinding(
            @Qualifier("aiConversationGenerationDetachQueue")
                    Queue aiConversationGenerationDetachQueue,
            @Qualifier("aiConversationGenerationDetachExchange")
                    CustomExchange aiConversationGenerationDetachExchange) {
        return BindingBuilder.bind(aiConversationGenerationDetachQueue)
                .to(aiConversationGenerationDetachExchange)
                .with(AiConversationGenerationRabbitNames.DETACH_ROUTING_KEY)
                .noargs();
    }

    @Bean
    Binding aiConversationGenerationTerminalBinding(
            @Qualifier("aiConversationGenerationTerminalQueue")
                    Queue aiConversationGenerationTerminalQueue,
            @Qualifier("aiConversationGenerationTerminalExchange")
                    DirectExchange aiConversationGenerationTerminalExchange) {
        return BindingBuilder.bind(aiConversationGenerationTerminalQueue)
                .to(aiConversationGenerationTerminalExchange)
                .with(AiConversationGenerationRabbitNames.TERMINAL_ROUTING_KEY);
    }

    @Bean
    Binding aiConversationGenerationTerminalV2Binding(
            @Qualifier("aiConversationGenerationTerminalV2Queue")
                    Queue terminalQueue,
            @Qualifier("aiConversationGenerationTerminalExchange")
                    DirectExchange terminalExchange) {
        return BindingBuilder.bind(terminalQueue)
                .to(terminalExchange)
                .with(AiConversationGenerationRabbitNames.TERMINAL_ROUTING_KEY_V2);
    }

    @Bean
    Binding aiConversationGenerationDeadLetterBinding(
            @Qualifier("aiConversationGenerationDeadLetterQueue")
                    Queue aiConversationGenerationDeadLetterQueue,
            @Qualifier("aiConversationGenerationDeadLetterExchange")
                    DirectExchange aiConversationGenerationDeadLetterExchange) {
        return BindingBuilder.bind(aiConversationGenerationDeadLetterQueue)
                .to(aiConversationGenerationDeadLetterExchange)
                .with(AiConversationGenerationRabbitNames.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean("aiConversationGenerationMessageConverter")
    MessageConverter aiConversationGenerationMessageConverter() {
        return new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build());
    }

    @Bean("aiConversationGenerationRabbitTemplate")
    RabbitTemplate aiConversationGenerationRabbitTemplate(
            ConnectionFactory connectionFactory,
            @Qualifier("aiConversationGenerationMessageConverter")
                    MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        // 延迟插件不能为未来路由可靠返回消息；即时生成、控制和终态消息必须启用 mandatory。
        rabbitTemplate.setMandatoryExpressionString(
                "messageProperties.headers['x-delay'] == null");
        return rabbitTemplate;
    }

    @Bean("aiConversationGenerationWorkerListenerFactory")
    SimpleRabbitListenerContainerFactory aiConversationGenerationWorkerListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("aiConversationGenerationMessageConverter")
                    MessageConverter messageConverter,
            AiConversationAsyncGenerationProperties properties) {
        return listenerFactory(
                configurer,
                connectionFactory,
                messageConverter,
                properties.workerConsumers());
    }

    @Bean("aiConversationGenerationControlListenerFactory")
    SimpleRabbitListenerContainerFactory aiConversationGenerationControlListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("aiConversationGenerationMessageConverter")
                    MessageConverter messageConverter) {
        return listenerFactory(configurer, connectionFactory, messageConverter, 1);
    }

    @Bean("aiConversationGenerationTerminalListenerFactory")
    SimpleRabbitListenerContainerFactory aiConversationGenerationTerminalListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("aiConversationGenerationMessageConverter")
                    MessageConverter messageConverter,
            AiConversationAsyncGenerationProperties properties) {
        return listenerFactory(
                configurer,
                connectionFactory,
                messageConverter,
                properties.workerConsumers());
    }

    private static Queue reliableQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deadLetterExchange(AiConversationGenerationRabbitNames.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(AiConversationGenerationRabbitNames.DEAD_LETTER_ROUTING_KEY)
                .build();
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
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(consumers);
        return factory;
    }
}
