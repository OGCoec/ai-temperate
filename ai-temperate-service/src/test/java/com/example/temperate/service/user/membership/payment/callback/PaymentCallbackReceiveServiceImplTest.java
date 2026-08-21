package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackReceiveServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 该单元测试是来约束模拟回调接收入口只写 Redis 队列，并校验时间窗、支付字段和完整回调快照。
 */
final class PaymentCallbackReceiveServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final HmacIdentifier FINGERPRINT = HmacIdentifier.fromProtectedValue(
            "oO1u7d8uvVC8w3fXbDgMDca8gTkO1HLQ_U-HtMxVQ0A");
    private static final HmacIdentifier PROVIDER_TRADE_FINGERPRINT =
            HmacIdentifier.fromProtectedValue(
                    "qO1u7d8uvVC8w3fXbDgMDca8gTkO1HLQ_U-HtMxVQ0A");

    private PaymentCallbackQueue callbackQueue;
    private HybridSemaphoreIdWorker idWorker;
    private PaymentCallbackReceiveService service;
    private String orderId;

    @BeforeEach
    void setUp() {
        callbackQueue = mock(PaymentCallbackQueue.class);
        PaymentCallbackFingerprintService fingerprintService =
                mock(PaymentCallbackFingerprintService.class);
        idWorker = mock(HybridSemaphoreIdWorker.class);
        byte[] orderBytes = bytes((byte) 5);
        byte[] callbackBytes = bytes((byte) 8);
        orderId = new HybridBase64UrlCodec().encode(orderBytes);
        when(idWorker.nextId()).thenReturn(callbackBytes);
        when(fingerprintService.fingerprint(any())).thenReturn(FINGERPRINT);
        when(fingerprintService.providerTradeFingerprint(any()))
                .thenReturn(PROVIDER_TRADE_FINGERPRINT);
        when(fingerprintService.payloadDigest(any()))
                .thenReturn("r6J7mFDrb9KH83hLrUkYQgt4AAJwxBkLBzsP4efjEKk");
        when(callbackQueue.enqueue(any(), any(), any())).thenAnswer(invocation -> {
            PaymentCallbackSnapshot snapshot = invocation.getArgument(0);
            return new PaymentCallbackEnqueueResult(
                    PaymentCallbackEnqueueOutcome.ENQUEUED,
                    snapshot.callbackId());
        });
        service = new PaymentCallbackReceiveServiceImpl(
                callbackQueue,
                fingerprintService,
                idWorker,
                new HybridBase64UrlCodec(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
    }

    @Test
    void validCallbackWritesCompleteBoundedSnapshotWithoutDatabaseDependency() {
        SimulatedLiuhaoCallbackResult result = service.receive(validCommand());

        ArgumentCaptor<PaymentCallbackSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(PaymentCallbackSnapshot.class);
        verify(callbackQueue).enqueue(
                snapshotCaptor.capture(), any(), any());
        PaymentCallbackSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(result.duplicate()).isFalse();
        assertThat(snapshot.orderId()).isEqualTo(orderId);
        assertThat(snapshot.channelTradeNo()).isEqualTo("channel-trade-1");
        assertThat(snapshot.paidAt().toInstant()).isEqualTo(NOW.minusSeconds(5));
        assertThat(snapshot.idempotencyFingerprint()).isEqualTo(FINGERPRINT.value());
    }

    @Test
    void callbackOutsideTimestampWindowIsRejectedBeforeRedis() {
        SimulatedLiuhaoCallbackCommand value = validCommand();
        SimulatedLiuhaoCallbackCommand expired = new SimulatedLiuhaoCallbackCommand(
                value.pid(), value.tradeNo(), value.outTradeNo(), value.apiTradeNo(),
                value.type(), value.tradeStatus(), value.addTime(), value.endTime(),
                value.name(), value.money(), value.param(), value.buyer(),
                Long.toString(NOW.minus(Duration.ofMinutes(6)).getEpochSecond()),
                value.sign(), value.signType());

        assertThatThrownBy(() -> service.receive(expired))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.INPUT_INVALID));
    }

    @Test
    void receiveImplementationConstructorHasNoMapperDependency() {
        assertThat(Arrays.stream(PaymentCallbackReceiveServiceImpl.class
                        .getConstructors()[0]
                        .getParameterTypes()))
                .noneMatch(type -> type.getPackageName().contains(".mapper"));
    }

    private SimulatedLiuhaoCallbackCommand validCommand() {
        return new SimulatedLiuhaoCallbackCommand(
                "merchant-test",
                "provider-trade-1",
                orderId,
                "channel-trade-1",
                "alipay",
                "TRADE_SUCCESS",
                "2026-08-20 11:59:50",
                "2026-08-20 11:59:55",
                "PLUS membership",
                "20.00",
                "",
                "",
                Long.toString(NOW.getEpochSecond()),
                "simulated-signature",
                "RSA");
    }

    private static byte[] bytes(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true,
                        "merchant-test",
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofMinutes(5),
                        16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
    }
}
