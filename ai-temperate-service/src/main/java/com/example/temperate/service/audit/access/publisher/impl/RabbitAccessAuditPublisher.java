package com.example.temperate.service.audit.access.publisher.impl;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;
import com.example.temperate.service.audit.access.publisher.AccessAuditPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 通过 durable direct Exchange 发布持久化访问审计消息，并异步记录固定结果标签的发布确认指标。
 */
@Component
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class RabbitAccessAuditPublisher implements AccessAuditPublisher {

    public static final String EXCHANGE = "ait.auth.access-audit.v1";
    public static final String ROUTING_KEY = "access.request.completed";

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;

    public RabbitAccessAuditPublisher(
            RabbitTemplate rabbitTemplate,
            MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(AccessRequestAuditMessage message) {
        CorrelationData correlation = new CorrelationData(message.messageId().toString());
        rabbitTemplate.convertAndSend(
                EXCHANGE,
                ROUTING_KEY,
                message,
                brokerMessage -> {
                    brokerMessage.getMessageProperties().setMessageId(
                            message.messageId().toString());
                    brokerMessage.getMessageProperties().setType(message.eventType());
                    brokerMessage.getMessageProperties().setDeliveryMode(
                            MessageDeliveryMode.PERSISTENT);
                    return brokerMessage;
                },
                correlation);
        correlation.getFuture().whenComplete((confirm, failure) -> {
            if (failure != null) {
                counter("confirm_error");
            } else if (!confirm.isAck()) {
                counter("nacked");
            } else if (correlation.getReturned() != null) {
                counter("returned");
            } else {
                counter("confirmed");
            }
        });
    }

    private void counter(String outcome) {
        meterRegistry.counter("auth.access_audit.rabbit_publish", "outcome", outcome).increment();
    }
}
