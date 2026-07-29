package com.example.temperate.web.admin.mailinspection.config;

import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 声明管理员邮箱检查的持久 Direct Exchange、四个 Quorum 工作队列、统一死信队列和专用异步监听基础设施。
 *
 * <p>四个监听器由业务服务显式启动，autoStartup 始终关闭；prefetch=1 让每个消费者只占用一个未确认业务额度。</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AdminMailInspectionRabbitConfiguration {

    @Bean
    DirectExchange adminMailInspectionWorkExchange() {
        return new DirectExchange(
                MailInspectionRabbitNames.WORK_EXCHANGE,
                true,
                false);
    }

    @Bean
    DirectExchange adminMailInspectionDeadExchange() {
        return new DirectExchange(
                MailInspectionRabbitNames.DEAD_EXCHANGE,
                true,
                false);
    }

    /**
     * Submission和派发Marker拓扑按检查类型成对声明；固定队列与单类型活动任务约束共同避免跨job混合。
     */
    @Bean
    Declarables adminMailInspectionSubmissionTopology() {
        DirectExchange submissionExchange = new DirectExchange(
                MailInspectionRabbitNames.SUBMISSION_EXCHANGE,
                true,
                false);
        DirectExchange dispatchStateExchange = new DirectExchange(
                MailInspectionRabbitNames.DISPATCH_STATE_EXCHANGE,
                true,
                false);
        DirectExchange deadExchange = new DirectExchange(
                MailInspectionRabbitNames.SUBMISSION_DEAD_EXCHANGE,
                true,
                false);
        Queue deadQueue = QueueBuilder
                .durable(MailInspectionRabbitNames.SUBMISSION_DEAD_QUEUE)
                .quorum()
                .build();
        List<org.springframework.amqp.core.Declarable> values =
                new ArrayList<>();
        values.add(submissionExchange);
        values.add(dispatchStateExchange);
        values.add(deadExchange);
        values.add(deadQueue);
        values.add(BindingBuilder.bind(deadQueue)
                .to(deadExchange)
                .with(MailInspectionRabbitNames.SUBMISSION_DEAD_ROUTING_KEY));
        for (var type : MailInspectionRabbitNames.supportedTypes()) {
            Queue submission = submissionQueue(
                    MailInspectionRabbitNames.submissionQueue(type));
            Queue marker = QueueBuilder
                    .durable(MailInspectionRabbitNames.dispatchStateQueue(type))
                    .quorum()
                    .build();
            values.add(submission);
            values.add(marker);
            values.add(BindingBuilder.bind(submission)
                    .to(submissionExchange)
                    .with(MailInspectionRabbitNames.submissionRoutingKey(type)));
            values.add(BindingBuilder.bind(marker)
                    .to(dispatchStateExchange)
                    .with(MailInspectionRabbitNames.dispatchStateRoutingKey(type)));
        }
        return new Declarables(values);
    }

    @Bean
    Queue adminMailInspectionOpenAiQueue() {
        return workQueue(MailInspectionRabbitNames.OPENAI_QUEUE);
    }

    @Bean
    Queue adminMailInspectionKiroQueue() {
        return workQueue(MailInspectionRabbitNames.KIRO_QUEUE);
    }

    @Bean
    Queue adminMailInspectionIp2RegistrationQueue() {
        return workQueue(
                MailInspectionRabbitNames.IP2_REGISTRATION_QUEUE);
    }

    @Bean
    Queue adminMailInspectionIp2VerifyQueue() {
        return workQueue(MailInspectionRabbitNames.IP2_VERIFY_QUEUE);
    }

    @Bean
    Queue adminMailInspectionDeadQueue() {
        return QueueBuilder
                .durable(MailInspectionRabbitNames.DEAD_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Binding adminMailInspectionOpenAiBinding(
            @Qualifier("adminMailInspectionOpenAiQueue") Queue queue,
            @Qualifier("adminMailInspectionWorkExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(MailInspectionRabbitNames.OPENAI_ROUTING_KEY);
    }

    @Bean
    Binding adminMailInspectionKiroBinding(
            @Qualifier("adminMailInspectionKiroQueue") Queue queue,
            @Qualifier("adminMailInspectionWorkExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(MailInspectionRabbitNames.KIRO_ROUTING_KEY);
    }

    @Bean
    Binding adminMailInspectionIp2RegistrationBinding(
            @Qualifier("adminMailInspectionIp2RegistrationQueue")
                    Queue queue,
            @Qualifier("adminMailInspectionWorkExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(
                        MailInspectionRabbitNames
                                .IP2_REGISTRATION_ROUTING_KEY);
    }

    @Bean
    Binding adminMailInspectionIp2VerifyBinding(
            @Qualifier("adminMailInspectionIp2VerifyQueue") Queue queue,
            @Qualifier("adminMailInspectionWorkExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(MailInspectionRabbitNames.IP2_VERIFY_ROUTING_KEY);
    }

    @Bean
    Binding adminMailInspectionDeadBinding(
            @Qualifier("adminMailInspectionDeadQueue") Queue queue,
            @Qualifier("adminMailInspectionDeadExchange")
                    DirectExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(MailInspectionRabbitNames.DEAD_ROUTING_KEY);
    }

    @Bean("adminMailInspectionMessageConverter")
    MessageConverter adminMailInspectionMessageConverter(
            ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean("adminMailInspectionRabbitTemplate")
    RabbitTemplate adminMailInspectionRabbitTemplate(
            ConnectionFactory connectionFactory,
            @Qualifier("adminMailInspectionMessageConverter")
                    MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean("adminMailInspectionListenerContainerFactory")
    SimpleRabbitListenerContainerFactory
            adminMailInspectionListenerContainerFactory(
                    SimpleRabbitListenerContainerFactoryConfigurer configurer,
                    ConnectionFactory connectionFactory,
                    @Qualifier("adminMailInspectionMessageConverter")
                            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(64);
        return factory;
    }

    @Bean("adminMailInspectionSubmissionListenerContainerFactory")
    SimpleRabbitListenerContainerFactory
            adminMailInspectionSubmissionListenerContainerFactory(
                    SimpleRabbitListenerContainerFactoryConfigurer configurer,
                    ConnectionFactory connectionFactory,
                    @Qualifier("adminMailInspectionMessageConverter")
                            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }

    private static Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-delivery-limit", 3)
                .deadLetterExchange(
                        MailInspectionRabbitNames.DEAD_EXCHANGE)
                .deadLetterRoutingKey(
                        MailInspectionRabbitNames.DEAD_ROUTING_KEY)
                .build();
    }

    private static Queue submissionQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-delivery-limit", 3)
                .deadLetterExchange(
                        MailInspectionRabbitNames.SUBMISSION_DEAD_EXCHANGE)
                .deadLetterRoutingKey(
                        MailInspectionRabbitNames.SUBMISSION_DEAD_ROUTING_KEY)
                .build();
    }
}
