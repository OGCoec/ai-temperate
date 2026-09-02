package com.example.temperate.service.user.membership.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackPersistenceService;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptOutcome;
import com.example.temperate.service.user.membership.payment.callback.PaymentRefundAttemptResult;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundMessagePublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;
import com.example.temperate.service.user.membership.payment.refund.impl.MembershipRefundAttemptCoordinatorImpl;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 该测试是来固定退款成功、明确失败、仅超时延迟以及 Confirm 失败补发时不重复调用 Provider 的编排合同。
 */
@ExtendWith(OutputCaptureExtension.class)
class MembershipRefundAttemptCoordinatorImplTest {

    private static final HybridBase64UrlCodec ID_CODEC = new HybridBase64UrlCodec();
    private static final String CALLBACK_ID = id((byte) 81);
    private static final String ORDER_ID = id((byte) 82);
    private static final String MESSAGE_ID = id((byte) 91);
    private static final PaymentRefundCommand COMMAND = new PaymentRefundCommand(
            ORDER_ID,
            "LIUHAO:TRADE:provider-trade",
            new BigDecimal("0.20"));

    private PaymentRefundCoordinationStore store;
    private MembershipPaymentRefundService refundService;
    private MembershipRefundMessagePublisher publisher;
    private MembershipRefundAttemptCoordinator coordinator;

    @BeforeEach
    void setUp() {
        store = mock(PaymentRefundCoordinationStore.class);
        refundService = mock(MembershipPaymentRefundService.class);
        publisher = mock(MembershipRefundMessagePublisher.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.rabbit()).thenReturn(new MembershipPaymentProperties.Rabbit(
                List.of(300_000L),
                List.of(300_000L),
                List.of(10_000L, 20_000L, 30_000L, 60_000L, 120_000L),
                Duration.ofSeconds(30),
                3));
        coordinator = new MembershipRefundAttemptCoordinatorImpl(
                store,
                refundService,
                publisher,
                mock(PaymentCallbackPersistenceService.class),
                ID_CODEC,
                properties);
        when(store.beginInitial(CALLBACK_ID)).thenReturn(attempting(1));
        when(publisher.newMessageId()).thenReturn(MESSAGE_ID);
    }

    @Test
    void successMarksCoordinationWithoutPublishing() {
        when(refundService.refund(COMMAND, 1)).thenReturn(result(
                PaymentRefundAttemptOutcome.SUCCEEDED, "VERIFIED", 1));
        when(store.markSucceeded(CALLBACK_ID, 1)).thenReturn(true);

        coordinator.processInitial(CALLBACK_ID, COMMAND);

        InOrder order = inOrder(refundService, store);
        order.verify(refundService).refund(COMMAND, 1);
        order.verify(store).markSucceeded(CALLBACK_ID, 1);
        verify(publisher, never()).publishRetry(anyString(), any(), any());
        verify(publisher, never()).publishTerminal(anyString(), any());
    }

    @Test
    void explicitFailurePublishesTerminalAndNeverRetry(CapturedOutput output) {
        when(refundService.refund(COMMAND, 1)).thenReturn(result(
                PaymentRefundAttemptOutcome.EXPLICIT_FAILURE,
                "LIUHAO_SIGNATURE_INVALID",
                1));
        when(store.prepareTerminal(
                CALLBACK_ID,
                1,
                MESSAGE_ID,
                PaymentRefundTerminalOutcome.EXPLICIT_FAILURE,
                "LIUHAO_SIGNATURE_INVALID")).thenReturn(true);
        when(store.confirmTerminal(CALLBACK_ID, MESSAGE_ID)).thenReturn(true);

        coordinator.processInitial(CALLBACK_ID, COMMAND);

        verify(publisher).publishTerminal(
                org.mockito.ArgumentMatchers.eq(MESSAGE_ID), any());
        verify(publisher, never()).publishRetry(anyString(), any(), any());
        verify(refundService, times(1)).refund(COMMAND, 1);
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_attempt")
                .contains("outcome=explicit_failure")
                .contains("retry_allowed=false")
                .contains("event=membership_payment_refund_terminal")
                .doesNotContain(CALLBACK_ID)
                .doesNotContain(ORDER_ID)
                .doesNotContain(COMMAND.providerTradeNo())
                .doesNotContain(COMMAND.amountYuan().toPlainString());
    }

    @Test
    void timeoutPublishesNextAttemptWithFirstDelay() {
        when(refundService.refund(COMMAND, 1)).thenReturn(result(
                PaymentRefundAttemptOutcome.TIMED_OUT, "LIUHAO_TIMEOUT", 1));
        when(store.prepareRetry(
                CALLBACK_ID, 1, MESSAGE_ID, 2, "LIUHAO_TIMEOUT"))
                .thenReturn(true);
        when(store.confirmRetry(CALLBACK_ID, MESSAGE_ID, 2)).thenReturn(true);

        coordinator.processInitial(CALLBACK_ID, COMMAND);

        verify(publisher).publishRetry(
                MESSAGE_ID,
                new MembershipRefundRetryMessage(CALLBACK_ID, 2, 6),
                Duration.ofSeconds(10));
        verify(publisher, never()).publishTerminal(anyString(), any());
    }

    @Test
    void sixthTimeoutPublishesExhaustedTerminalWithoutRetry() {
        when(store.beginInitial(CALLBACK_ID)).thenReturn(attempting(6));
        when(refundService.refund(COMMAND, 6)).thenReturn(result(
                PaymentRefundAttemptOutcome.TIMED_OUT, "LIUHAO_TIMEOUT", 6));
        when(store.prepareTerminal(
                CALLBACK_ID,
                6,
                MESSAGE_ID,
                PaymentRefundTerminalOutcome.TIMEOUT_EXHAUSTED,
                "TIMEOUT_EXHAUSTED")).thenReturn(true);
        when(store.confirmTerminal(CALLBACK_ID, MESSAGE_ID)).thenReturn(true);

        coordinator.processInitial(CALLBACK_ID, COMMAND);

        verify(publisher).publishTerminal(
                org.mockito.ArgumentMatchers.eq(MESSAGE_ID), any());
        verify(publisher, never()).publishRetry(anyString(), any(), any());
    }

    @Test
    void confirmFailureLeavesPendingStateAndNextCallbackOnlyRepublishes() {
        when(refundService.refund(COMMAND, 1)).thenReturn(result(
                PaymentRefundAttemptOutcome.TIMED_OUT, "LIUHAO_TIMEOUT", 1));
        when(store.prepareRetry(
                CALLBACK_ID, 1, MESSAGE_ID, 2, "LIUHAO_TIMEOUT"))
                .thenReturn(true);
        doThrow(new IllegalStateException("confirm unavailable"))
                .when(publisher).publishRetry(anyString(), any(), any());

        assertThatThrownBy(() -> coordinator.processInitial(CALLBACK_ID, COMMAND))
                .isInstanceOf(IllegalStateException.class);

        when(store.beginInitial(CALLBACK_ID)).thenReturn(
                new PaymentRefundCoordinationDecision(
                        PaymentRefundCoordinationAction.PUBLISH_RETRY,
                        1,
                        MESSAGE_ID,
                        2,
                        null,
                        "LIUHAO_TIMEOUT"));
        org.mockito.Mockito.reset(publisher);
        when(store.confirmRetry(CALLBACK_ID, MESSAGE_ID, 2)).thenReturn(true);

        coordinator.processInitial(CALLBACK_ID, COMMAND);

        verify(refundService, times(1)).refund(COMMAND, 1);
        verify(publisher).publishRetry(
                MESSAGE_ID,
                new MembershipRefundRetryMessage(CALLBACK_ID, 2, 6),
                Duration.ofSeconds(10));
    }

    @Test
    void staleRetryMessageDoesNotCallProvider() {
        MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        MESSAGE_ID,
                        MembershipPaymentRabbitNames.REFUND_RETRY_EVENT,
                        MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                        OffsetDateTime.parse("2026-09-01T12:00:00Z"),
                        "trace-test",
                        new MembershipRefundRetryMessage(CALLBACK_ID, 2, 6));
        PaymentCallbackPersistenceService persistence = mock(
                PaymentCallbackPersistenceService.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.rabbit()).thenReturn(new MembershipPaymentProperties.Rabbit(
                List.of(300_000L),
                List.of(300_000L),
                List.of(10_000L, 20_000L, 30_000L, 60_000L, 120_000L),
                Duration.ofSeconds(30),
                3));
        coordinator = new MembershipRefundAttemptCoordinatorImpl(
                store, refundService, publisher, persistence, ID_CODEC, properties);
        when(persistence.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(java.util.Map.of());
        when(store.claimRetry(CALLBACK_ID, 2, MESSAGE_ID)).thenReturn(
                new PaymentRefundCoordinationDecision(
                        PaymentRefundCoordinationAction.STALE_MESSAGE,
                        3,
                        MESSAGE_ID,
                        0,
                        null,
                        null));

        coordinator.processRetry(envelope);

        verify(refundService, never()).refund(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(publisher, never()).publishRetry(anyString(), any(), any());
        verify(publisher, never()).publishTerminal(anyString(), any());
    }

    @Test
    void redeliveredRetryAfterPublishFailureOnlyRepublishesPendingMessage() {
        String nextMessageId = id((byte) 92);
        MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        MESSAGE_ID,
                        MembershipPaymentRabbitNames.REFUND_RETRY_EVENT,
                        1,
                        OffsetDateTime.parse("2026-09-01T12:00:00Z"),
                        "trace-redelivery",
                        new MembershipRefundRetryMessage(CALLBACK_ID, 2, 6));
        PaymentCallbackPersistenceService persistence = mock(
                PaymentCallbackPersistenceService.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.rabbit()).thenReturn(new MembershipPaymentProperties.Rabbit(
                List.of(300_000L),
                List.of(300_000L),
                List.of(10_000L, 20_000L, 30_000L, 60_000L, 120_000L),
                Duration.ofSeconds(30),
                3));
        coordinator = new MembershipRefundAttemptCoordinatorImpl(
                store, refundService, publisher, persistence, ID_CODEC, properties);
        when(persistence.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(java.util.Map.of());
        when(store.claimRetry(CALLBACK_ID, 2, MESSAGE_ID)).thenReturn(
                new PaymentRefundCoordinationDecision(
                        PaymentRefundCoordinationAction.PUBLISH_RETRY,
                        2,
                        nextMessageId,
                        3,
                        null,
                        "LIUHAO_TIMEOUT"));
        when(store.confirmRetry(CALLBACK_ID, nextMessageId, 3)).thenReturn(true);

        coordinator.processRetry(envelope);

        verify(refundService, never()).refund(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(publisher).publishRetry(
                nextMessageId,
                new MembershipRefundRetryMessage(CALLBACK_ID, 3, 6),
                Duration.ofSeconds(20));
    }

    private static PaymentRefundCoordinationDecision attempting(int attemptNo) {
        return new PaymentRefundCoordinationDecision(
                PaymentRefundCoordinationAction.ATTEMPT_PROVIDER,
                attemptNo,
                null,
                0,
                null,
                null);
    }

    private static PaymentRefundAttemptResult result(
            PaymentRefundAttemptOutcome outcome, String reason, int attemptNo) {
        return new PaymentRefundAttemptResult(
                outcome, reason, PaymentProviderType.LIUHAO, attemptNo);
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        java.util.Arrays.fill(bytes, value);
        return ID_CODEC.encode(bytes);
    }
}
