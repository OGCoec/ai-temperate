package com.example.temperate.service.audit.access.consumer;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;
import com.example.temperate.model.audit.access.AccessRequestAuditPayload;
import com.example.temperate.service.audit.access.store.AccessRequestAuditStoreService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * 验证访问审计消费者先完成整批数据库事务调用，再对同批 RabbitMQ 消息执行手动确认。
 */
class AccessAuditConsumerTest {

    @Test
    void acknowledgesOnlyAfterTheBatchStoreReturns() throws Exception {
        AccessRequestAuditStoreService storeService = mock(AccessRequestAuditStoreService.class);
        MessageConverter converter = mock(MessageConverter.class);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        Message brokerMessage = new Message(new byte[0], properties);
        when(converter.fromMessage(brokerMessage)).thenReturn(message());
        AccessAuditConsumer consumer = new AccessAuditConsumer(
                storeService, converter, new SimpleMeterRegistry());

        consumer.consume(List.of(brokerMessage), channel);

        InOrder order = inOrder(storeService, channel);
        order.verify(storeService).storeBatch(org.mockito.ArgumentMatchers.anyList());
        order.verify(channel).basicAck(7L, false);
    }

    private static AccessRequestAuditMessage message() {
        return new AccessRequestAuditMessage(
                UUID.randomUUID(),
                AccessRequestAuditMessage.EVENT_TYPE,
                AccessRequestAuditMessage.SCHEMA_VERSION,
                Instant.parse("2026-07-16T10:00:00Z"),
                UUID.randomUUID(),
                new AccessRequestAuditPayload(
                        10001L,
                        "GET",
                        "/api/users/me",
                        200,
                        10L,
                        "H5",
                        4,
                        "203.0.113.0/24",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }
}
