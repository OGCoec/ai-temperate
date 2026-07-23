package com.example.temperate.service.audit.access.consumer;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;
import com.example.temperate.model.audit.access.AccessRequestAuditPayload;
import com.example.temperate.model.audit.access.AccessRequestAuditRecord;
import com.example.temperate.service.audit.access.store.AccessRequestAuditStoreService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 批量消费访问审计消息，在 PostgreSQL 事务提交成功后逐条手动 ACK，失败则交由有限重试和死信策略处理。
 */
@Component
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessAuditConsumer {

    public static final String STORE_QUEUE = "ait.auth.access-audit.store.v1";

    private final AccessRequestAuditStoreService storeService;
    private final MessageConverter messageConverter;
    private final MeterRegistry meterRegistry;

    public AccessAuditConsumer(
            AccessRequestAuditStoreService storeService,
            @Qualifier("accessAuditMessageConverter") MessageConverter messageConverter,
            MeterRegistry meterRegistry) {
        this.storeService = storeService;
        this.messageConverter = messageConverter;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(
            queues = STORE_QUEUE,
            containerFactory = "accessAuditRabbitListenerContainerFactory")
    public void consume(List<Message> brokerMessages, Channel channel) throws IOException {
        if (brokerMessages == null || brokerMessages.isEmpty()) {
            return;
        }
        List<AccessRequestAuditRecord> records = new ArrayList<>(brokerMessages.size());
        for (Message brokerMessage : brokerMessages) {
            Object converted = messageConverter.fromMessage(brokerMessage);
            if (!(converted instanceof AccessRequestAuditMessage message)) {
                throw new IllegalArgumentException("Unsupported access audit message payload.");
            }
            records.add(toRecord(message));
        }

        // 批量 SQL 的事务已提交后才确认消息；提交前抛出的异常由监听容器重试，最终拒绝进入死信队列。
        storeService.storeBatch(records);
        for (Message brokerMessage : brokerMessages) {
            channel.basicAck(brokerMessage.getMessageProperties().getDeliveryTag(), false);
        }
        meterRegistry.counter("auth.access_audit.consume", "outcome", "stored")
                .increment(brokerMessages.size());
    }

    private static AccessRequestAuditRecord toRecord(AccessRequestAuditMessage message) {
        if (!AccessRequestAuditMessage.EVENT_TYPE.equals(message.eventType())
                || message.schemaVersion() != AccessRequestAuditMessage.SCHEMA_VERSION
                || message.messageId() == null
                || message.traceId() == null
                || message.occurredAt() == null
                || message.payload() == null) {
            throw new IllegalArgumentException("Invalid access audit message envelope.");
        }
        AccessRequestAuditPayload payload = message.payload();
        return new AccessRequestAuditRecord(
                message.messageId(),
                payload.userId(),
                message.traceId(),
                message.occurredAt(),
                payload.method(),
                payload.routeTemplate(),
                payload.statusCode(),
                payload.durationMillis(),
                payload.clientPlatform(),
                payload.ipFamily(),
                payload.ipPrefix(),
                payload.ipHmac());
    }
}
