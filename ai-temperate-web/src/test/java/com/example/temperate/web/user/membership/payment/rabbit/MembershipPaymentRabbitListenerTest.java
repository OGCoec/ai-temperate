package com.example.temperate.web.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentOperation;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingRecorder;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingStep;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededCloseConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededCloseMessage;
import com.rabbitmq.client.Channel;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 该单元测试是来约束 Rabbit 监听器只在业务成功后 ACK，失败时交给 Quorum Queue 三次有限投递后进入 DLQ。
 */
final class MembershipPaymentRabbitListenerTest {

    private MembershipPaymentCheckConsumerService paymentService;
    private Channel channel;
    private MembershipPaymentTimingRecorder timingRecorder;
    private MembershipSupersededCloseConsumerService supersededCloseService;
    private MembershipPaymentTimingRecorder.Session timingSession;
    private MembershipPaymentRabbitListener listener;
    private MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope;
    private Message message;

    @BeforeEach
    void setUp() {
        paymentService = mock(MembershipPaymentCheckConsumerService.class);
        channel = mock(Channel.class);
        timingRecorder = mock(MembershipPaymentTimingRecorder.class);
        supersededCloseService = mock(MembershipSupersededCloseConsumerService.class);
        timingSession = mock(MembershipPaymentTimingRecorder.Session.class);
        when(timingRecorder.start(
                eq(MembershipPaymentOperation.RABBIT_PENDING),
                any(Object[].class)))
                .thenReturn(timingSession);
        listener = new MembershipPaymentRabbitListener(
                paymentService,
                mock(MembershipClosingCheckConsumerService.class),
                supersededCloseService,
                mock(MembershipPaymentMetrics.class),
                timingRecorder);
        envelope = new MembershipPaymentRabbitEnvelope<>(
                id((byte) 3),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                1,
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-test",
                new MembershipPaymentCheckMessage(id((byte) 2), 0));
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(41L);
        message = new Message(new byte[0], properties);
    }

    @AfterEach
    void clearLoggingContext() {
        MDC.clear();
    }

    @Test
    void businessSuccessAcknowledgesAfterProcessing() throws Exception {
        listener.consumePayment(envelope, message, channel);

        verify(paymentService).process(envelope);
        verify(channel).basicAck(41L, false);
        verify(channel, never()).basicNack(41L, false, true);
        verify(timingRecorder).recordStep(
                eq(MembershipPaymentTimingStep.RABBIT_ACK), anyLong(), eq(true));
        verify(timingRecorder).markRabbitOutcome("ACK", 0L);
        verify(timingRecorder).finish(timingSession, null, null);
    }

    @Test
    void businessFailureUsesQuorumFiniteRedelivery() throws Exception {
        IllegalStateException failure = new IllegalStateException("confirm failed");
        doThrow(failure).when(paymentService).process(envelope);

        listener.consumePayment(envelope, message, channel);

        verify(channel, never()).basicAck(41L, false);
        verify(channel).basicNack(41L, false, true);
        verify(timingRecorder).recordStep(
                eq(MembershipPaymentTimingStep.RABBIT_ACK), anyLong(), eq(true));
        verify(timingRecorder).markRabbitOutcome("NACK", 0L);
        verify(timingRecorder).markFailure(failure);
        verify(timingRecorder).finish(timingSession, null, null);
    }

    @Test
    void installsEnvelopeContextAndRestoresWorkerContext() throws Exception {
        MDC.put("traceId", "worker-trace");
        MDC.put("messageId", "worker-message");
        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo(envelope.traceId());
            assertThat(MDC.get("messageId")).isEqualTo(envelope.messageId());
            return null;
        }).when(paymentService).process(envelope);

        listener.consumePayment(envelope, message, channel);

        assertThat(MDC.get("traceId")).isEqualTo("worker-trace");
        assertThat(MDC.get("messageId")).isEqualTo("worker-message");
    }

    @Test
    void loadtestRetryProbeNacksItsFirstTwoDeliveries() throws Exception {
        listener = loadtestListener();
        envelope = probeEnvelope(MembershipPaymentRabbitNames.LOADTEST_RETRY_EVENT);
        message.getMessageProperties().setHeader("x-delivery-count", 1L);

        listener.consumePayment(envelope, message, channel);

        verify(paymentService, never()).process(envelope);
        verify(channel).basicNack(41L, false, true);
    }

    @Test
    void loadtestRetryProbeAcknowledgesThirdDeliveryWithoutBusinessMutation() throws Exception {
        listener = loadtestListener();
        envelope = probeEnvelope(MembershipPaymentRabbitNames.LOADTEST_RETRY_EVENT);
        message.getMessageProperties().setHeader("x-delivery-count", 2L);

        listener.consumePayment(envelope, message, channel);

        verify(paymentService, never()).process(envelope);
        verify(channel).basicAck(41L, false);
    }

    @Test
    void loadtestPoisonProbeAlwaysFollowsFiniteDlqRoute() throws Exception {
        listener = loadtestListener();
        envelope = probeEnvelope(MembershipPaymentRabbitNames.LOADTEST_POISON_EVENT);

        listener.consumePayment(envelope, message, channel);

        verify(paymentService, never()).process(envelope);
        verify(channel).basicNack(41L, false, true);
    }

    @Test
    void supersededCloseAcknowledgesOnlyAfterBackgroundCloseProcessing() throws Exception {
        MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> closeEnvelope =
                new MembershipPaymentRabbitEnvelope<>(
                        id((byte) 5),
                        MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EVENT,
                        1,
                        OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                        "trace-superseded",
                        new MembershipSupersededCloseMessage(id((byte) 2), 0));
        when(timingRecorder.start(
                eq(MembershipPaymentOperation.RABBIT_CLOSING),
                any(Object[].class)))
                .thenReturn(timingSession);

        listener.consumeSupersededClose(closeEnvelope, message, channel);

        verify(supersededCloseService).process(closeEnvelope);
        verify(channel).basicAck(41L, false);
    }

    @Test
    void refundRetryAcknowledgesOnlyAfterCoordinatorReturns() throws Exception {
        MembershipRefundRetryConsumerService refundService =
                mock(MembershipRefundRetryConsumerService.class);
        listener = new MembershipPaymentRabbitListener(
                paymentService,
                mock(MembershipClosingCheckConsumerService.class),
                supersededCloseService,
                refundService,
                mock(MembershipPaymentMetrics.class),
                timingRecorder,
                new MembershipPaymentLoadtestProperties(false, List.of()));
        MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> refundEnvelope =
                new MembershipPaymentRabbitEnvelope<>(
                        id((byte) 6),
                        MembershipPaymentRabbitNames.REFUND_RETRY_EVENT,
                        1,
                        OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                        "trace-refund",
                        new MembershipRefundRetryMessage(id((byte) 7), 2, 6));

        listener.consumeRefundRetry(refundEnvelope, message, channel);

        verify(refundService).process(refundEnvelope);
        verify(channel).basicAck(41L, false);
        verify(channel, never()).basicNack(41L, false, true);
    }

    private MembershipPaymentRabbitListener loadtestListener() {
        return new MembershipPaymentRabbitListener(
                paymentService,
                mock(MembershipClosingCheckConsumerService.class),
                supersededCloseService,
                mock(MembershipPaymentMetrics.class),
                timingRecorder,
                new MembershipPaymentLoadtestProperties(true, List.of(1L)));
    }

    private MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> probeEnvelope(
            String eventType) {
        return new MembershipPaymentRabbitEnvelope<>(
                id((byte) 4),
                eventType,
                1,
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-probe",
                new MembershipPaymentCheckMessage(id((byte) 2), 0));
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
