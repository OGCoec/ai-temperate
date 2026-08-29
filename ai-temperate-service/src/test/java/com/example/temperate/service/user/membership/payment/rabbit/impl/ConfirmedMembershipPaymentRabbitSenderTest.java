package com.example.temperate.service.user.membership.payment.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRabbitPublishBreakdown;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingRecorder;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitConfirmCoordinator;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来约束既有 Sender 接口完整委托有界 Confirm 协调器，并原样传播对应消息的受控失败。
 */
final class ConfirmedMembershipPaymentRabbitSenderTest {

    @Test
    void delegatesExactMessageAndDelayToBoundedConfirmCoordinator() {
        MembershipPaymentRabbitConfirmCoordinator coordinator =
                mock(MembershipPaymentRabbitConfirmCoordinator.class);
        MembershipPaymentTimingRecorder recorder = mock(MembershipPaymentTimingRecorder.class);
        MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope = envelope();
        MembershipPaymentRabbitPublishBreakdown breakdown =
                new MembershipPaymentRabbitPublishBreakdown(3L, 5L, 1);
        org.mockito.Mockito.when(coordinator.publishAndAwait(
                        MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                        MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                        envelope,
                        Duration.ofSeconds(10)))
                .thenReturn(breakdown);

        new ConfirmedMembershipPaymentRabbitSender(coordinator, recorder).send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope,
                Duration.ofSeconds(10));
        verify(recorder).recordRabbitPublishBreakdown(breakdown);

        verify(coordinator).publishAndAwait(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope,
                Duration.ofSeconds(10));
    }

    @Test
    void brokerNackProducesControlledRabbitUnavailableError() {
        MembershipPaymentRabbitConfirmCoordinator coordinator =
                mock(MembershipPaymentRabbitConfirmCoordinator.class);
        MembershipPaymentTimingRecorder recorder = mock(MembershipPaymentTimingRecorder.class);
        doThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                "not confirmed"))
                .when(coordinator)
                .publishAndAwait(anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> new ConfirmedMembershipPaymentRabbitSender(coordinator, recorder).send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope(),
                Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE));
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope() {
        return new MembershipPaymentRabbitEnvelope<>(
                id((byte) 3),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                1,
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-test",
                new MembershipPaymentCheckMessage(id((byte) 2), 0));
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
