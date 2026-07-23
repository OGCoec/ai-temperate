package com.example.temperate.web.audit.access.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.example.temperate.service.audit.access.component.AccessAuditIpProtector;
import com.example.temperate.service.audit.access.config.AccessAuditProperties;
import com.example.temperate.service.audit.access.consumer.AccessAuditConsumer;
import com.example.temperate.service.audit.access.publisher.impl.RabbitAccessAuditPublisher;
import java.util.Base64;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * 配置访问审计的独立 HMAC、RabbitMQ durable/quorum 拓扑、批量手动确认和三次有限重试。
 *
 * <p>该拓扑不提供 Exactly Once；数据库以消息 ID 幂等，发布确认和死信队列只缩小不可避免的故障窗口。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AccessAuditProperties.class)
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public class AccessAuditConfiguration {

    public static final String DEAD_LETTER_EXCHANGE = "ait.auth.access-audit.dlx.v1";
    public static final String DEAD_QUEUE = "ait.auth.access-audit.dead.v1";
    public static final String DEAD_ROUTING_KEY = "access.request.dead";

    @Bean
    AccessAuditIpProtector accessAuditIpProtector(AccessAuditProperties properties) {
        String encoded = properties.hmacSecretBase64();
        if (!StringUtils.hasText(encoded)) {
            throw new IllegalStateException("ACCESS_AUDIT_HMAC_SECRET_BASE64 is required.");
        }
        try {
            return new AccessAuditIpProtector(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "ACCESS_AUDIT_HMAC_SECRET_BASE64 must be canonical Base64 with at least 32 decoded bytes.",
                    exception);
        }
    }

    @Bean
    DirectExchange accessAuditExchange() {
        return new DirectExchange(RabbitAccessAuditPublisher.EXCHANGE, true, false);
    }

    @Bean
    DirectExchange accessAuditDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue accessAuditStoreQueue() {
        return QueueBuilder.durable(AccessAuditConsumer.STORE_QUEUE)
                .quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue accessAuditDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).quorum().build();
    }

    @Bean
    Binding accessAuditStoreBinding(
            Queue accessAuditStoreQueue,
            DirectExchange accessAuditExchange) {
        return BindingBuilder.bind(accessAuditStoreQueue)
                .to(accessAuditExchange)
                .with(RabbitAccessAuditPublisher.ROUTING_KEY);
    }

    @Bean
    Binding accessAuditDeadBinding(
            Queue accessAuditDeadQueue,
            DirectExchange accessAuditDeadLetterExchange) {
        return BindingBuilder.bind(accessAuditDeadQueue)
                .to(accessAuditDeadLetterExchange)
                .with(DEAD_ROUTING_KEY);
    }

    @Bean("accessAuditMessageConverter")
    MessageConverter accessAuditMessageConverter() {
        return new Jackson2JsonMessageConverter(
                JsonMapper.builder().findAndAddModules().build());
    }

    @Bean("accessAuditRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory accessAuditRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("accessAuditMessageConverter") MessageConverter messageConverter,
            AccessAuditProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchListener(true);
        factory.setBatchSize(properties.batchSize());
        // Prefetch 与单批上限一致，避免下一批消息在当前数据库事务期间提前占用 ACK 计时窗口。
        factory.setPrefetchCount(properties.batchSize());
        long batchTimeoutMillis = properties.batchTimeout().toMillis();
        factory.setReceiveTimeout(batchTimeoutMillis);
        // 限制整个批次的最大等待时间，避免低流量时已投递消息长期占用未确认窗口。
        factory.setBatchReceiveTimeout(batchTimeoutMillis);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        Advice retry = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(200L, 2.0, 1000L)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(retry);
        return factory;
    }
}
