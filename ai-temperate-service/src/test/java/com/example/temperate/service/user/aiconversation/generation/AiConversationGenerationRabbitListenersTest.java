package com.example.temperate.service.user.aiconversation.generation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingConsumer;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationMessageRejectedException;
import com.example.temperate.service.user.aiconversation.generation.rabbit.impl.AiConversationGenerationRabbitListeners;
import com.example.temperate.service.user.aiconversation.generation.recovery.AiConversationGenerationRecoveryService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorker;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.rabbitmq.client.Channel;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 验证 Generation 消费者只在业务成功后手动 ACK，失败固定拒绝进入 DLQ 而不无限重新入队。
 */
final class AiConversationGenerationRabbitListenersTest {

    private AiConversationGenerationWorker worker;
    private AiConversationMetrics metrics;
    private AiConversationGenerationBillingConsumer billingConsumer;
    private AiConversationGenerationRecoveryService recoveryService;
    private AiConversationGenerationRabbitListeners listeners;

    @BeforeEach
    void setUp() {
        worker = mock(AiConversationGenerationWorker.class);
        metrics = mock(AiConversationMetrics.class);
        billingConsumer = mock(AiConversationGenerationBillingConsumer.class);
        recoveryService = mock(AiConversationGenerationRecoveryService.class);
        listeners = new AiConversationGenerationRabbitListeners(
                worker,
                mock(AiConversationGenerationActiveRegistry.class),
                mock(AiConversationGenerationControlService.class),
                mock(AiConversationGenerationCancellationService.class),
                billingConsumer,
                recoveryService,
                new HybridBase64UrlCodec(),
                metrics);
    }

    @Test
    void acknowledgesOnlyAfterWorkerCompletes() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = message(17L);

        listeners.generation(envelope(), message, channel);

        verify(worker).execute("AAAAAAAAAAAAAAAAAAAAAA", "trace-test");
        verify(channel).basicAck(17L, false);
    }

    @Test
    void rejectsWithoutRequeueWhenWorkerFails() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = message(18L);
        doThrow(new IllegalStateException("controlled-test-failure"))
                .when(worker)
                .execute("AAAAAAAAAAAAAAAAAAAAAA", "trace-test");

        listeners.generation(envelope(), message, channel);

        verify(channel).basicReject(18L, false);
        verify(metrics).rabbitDeadLetter();
    }

    @Test
    void authorityMismatchIsDeadLetteredWithoutMarkingBillingForReconciliation()
            throws Exception {
        Channel channel = mock(Channel.class);
        Message message = message(19L);
        AiConversationGenerationEnvelope<AiConversationGenerationTerminated> terminal =
                AiConversationGenerationEnvelope.of(
                        UUID.randomUUID(),
                        "AI_GENERATION_TERMINATED",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        "trace-test",
                        new AiConversationGenerationTerminated(
                                "AAAAAAAAAAAAAAAAAAAAAA",
                                "BBBBBBBBBBBBBBBBBBBBBB",
                                "COMPLETED",
                                "STOP",
                                1));
        doThrow(new AiConversationGenerationMessageRejectedException("mismatch"))
                .when(billingConsumer)
                .consume(terminal.payload(), "trace-test");

        listeners.terminal(terminal, message, channel);

        verify(channel).basicReject(19L, false);
        verifyNoInteractions(recoveryService);
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], properties);
    }

    private static AiConversationGenerationEnvelope<AiConversationGenerationRequested>
            envelope() {
        return AiConversationGenerationEnvelope.of(
                UUID.randomUUID(),
                "AI_GENERATION_REQUESTED",
                OffsetDateTime.now(ZoneOffset.UTC),
                "trace-test",
                new AiConversationGenerationRequested(
                        "AAAAAAAAAAAAAAAAAAAAAA",
                        "BBBBBBBBBBBBBBBBBBBBBB"));
    }
}
