package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentCallbackDecisionServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来锁定 4:59/5:00/9:59/10:00、终态与数据库唯一性之后的防御性裁决边界。
 */
final class MembershipPaymentCallbackDecisionServiceImplTest {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final String ORDER_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);
    private final MembershipPaymentCallbackDecisionService service =
            new MembershipPaymentCallbackDecisionServiceImpl(properties());

    @Test
    void fourFiftyNineStartAndFiveOhNineCallbackApplies() {
        assertThat(decide(
                        MembershipOrderStatus.CLOSING,
                        CREATED.plusMinutes(4).plusSeconds(59),
                        CREATED.plusMinutes(5).plusSeconds(9),
                        CREATED.plusMinutes(5).plusSeconds(9),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.APPLIED);
    }

    @Test
    void fourFiftyNineStartAndNineFiftyNineCallbackApplies() {
        MembershipPaymentCallbackDecision decision = decide(
                MembershipOrderStatus.CLOSING,
                CREATED.plusMinutes(4).plusSeconds(59),
                CREATED.plusMinutes(9).plusSeconds(58),
                CREATED.plusMinutes(9).plusSeconds(59),
                "trade-1");

        assertThat(decision.applyPayment()).isTrue();
        assertThat(decision.resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.APPLIED);
    }

    @Test
    void missingStartIsRejectedAndExactTenMinuteReceiptRequiresRefund() {
        assertThat(decide(
                        MembershipOrderStatus.PENDING_PAYMENT,
                        null,
                        CREATED.plusMinutes(5),
                        CREATED.plusMinutes(5),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
        assertThat(decide(
                        MembershipOrderStatus.CLOSING,
                        CREATED.plusMinutes(4).plusSeconds(59),
                        CREATED.plusMinutes(9).plusSeconds(59),
                        CREATED.plusMinutes(10),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REFUND_REQUIRED);
    }

    @Test
    void exactExpiryReceiptAppliesAfterAValidPreExpiryPaymentStart() {
        assertThat(decide(
                        MembershipOrderStatus.CLOSING,
                        CREATED.plusMinutes(4).plusSeconds(59),
                        CREATED.plusMinutes(5),
                        CREATED.plusMinutes(5),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.APPLIED);
    }

    @Test
    void paymentStartAtExactExpiryCannotBecomeAPersistedOrderSnapshot() {
        assertThatThrownBy(() -> order(
                        MembershipOrderStatus.CLOSING,
                        CREATED.plusMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership payment must start before the order expires.");
    }

    @Test
    void cancelledAndClosedRequireRefundWhilePaidFallbackNeverCreatesAnotherRefund() {
        assertThat(decide(
                        MembershipOrderStatus.CANCELLED,
                        CREATED.plusMinutes(1),
                        CREATED.plusMinutes(2),
                        CREATED.plusMinutes(2),
                        "trade-1").refundRequired())
                .isTrue();
        assertThat(decide(
                        MembershipOrderStatus.CLOSED,
                        CREATED.plusMinutes(1),
                        CREATED.plusMinutes(2),
                        CREATED.plusMinutes(2),
                        "trade-1").refundRequired())
                .isTrue();
        assertThat(decide(
                        MembershipOrderStatus.PAID,
                        CREATED.plusMinutes(1),
                        CREATED.plusMinutes(2),
                        CREATED.plusMinutes(2),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
        assertThat(decide(
                        MembershipOrderStatus.PAID,
                        CREATED.plusMinutes(1),
                        CREATED.plusMinutes(2),
                        CREATED.plusMinutes(2),
                        "trade-2").refundRequired())
                .isFalse();
    }

    @Test
    void paidTimeBeforeStartOrAfterReceiptIsRejected() {
        OffsetDateTime startedAt = CREATED.plusMinutes(1);
        assertThat(decide(
                        MembershipOrderStatus.PENDING_PAYMENT,
                        startedAt,
                        startedAt.minusNanos(1),
                        startedAt.plusSeconds(1),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
        assertThat(decide(
                        MembershipOrderStatus.PENDING_PAYMENT,
                        startedAt,
                        startedAt.plusSeconds(2),
                        startedAt.plusSeconds(1),
                        "trade-1").resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
    }

    @Test
    void amountOrSupportedPaymentTypeMismatchIsPersistedButRejected() {
        MembershipOrderSnapshot order = order(
                MembershipOrderStatus.PENDING_PAYMENT,
                CREATED.plusMinutes(1));
        PaymentCallbackSnapshot wrongAmount = callback(
                CREATED.plusMinutes(2),
                CREATED.plusMinutes(2),
                "trade-1",
                new BigDecimal("20.01"),
                "alipay");
        PaymentCallbackSnapshot wrongPayType = callback(
                CREATED.plusMinutes(2),
                CREATED.plusMinutes(2),
                "trade-1",
                new BigDecimal("20.00"),
                "wxpay");

        assertThat(service.decide(order, wrongAmount).resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
        assertThat(service.decide(order, wrongPayType).resolution())
                .isEqualTo(MembershipPaymentCallbackResolution.REJECTED);
    }

    private MembershipPaymentCallbackDecision decide(
            MembershipOrderStatus status,
            OffsetDateTime startedAt,
            OffsetDateTime paidAt,
            OffsetDateTime receivedAt,
            String providerTradeNo) {
        return service.decide(order(status, startedAt), callback(
                paidAt, receivedAt, providerTradeNo));
    }

    private static MembershipOrderSnapshot order(
            MembershipOrderStatus status,
            OffsetDateTime paymentStartedAt) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                status == MembershipOrderStatus.PAID ? "trade-1" : null,
                paymentStartedAt,
                CREATED.plusMinutes(5),
                status == MembershipOrderStatus.CLOSING ? CREATED.plusMinutes(10) : null,
                status == MembershipOrderStatus.PAID ? CREATED.plusMinutes(2) : null,
                status == MembershipOrderStatus.PENDING_PAYMENT ? 2L : 3L,
                CREATED,
                CREATED.plusMinutes(5));
    }

    private static PaymentCallbackSnapshot callback(
            OffsetDateTime paidAt,
            OffsetDateTime receivedAt,
            String providerTradeNo) {
        return callback(
                paidAt,
                receivedAt,
                providerTradeNo,
                new BigDecimal("20.00"),
                "alipay");
    }

    private static PaymentCallbackSnapshot callback(
            OffsetDateTime paidAt,
            OffsetDateTime receivedAt,
            String providerTradeNo,
            BigDecimal paidAmountYuan,
            String payType) {
        return new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                CALLBACK_ID,
                ORDER_ID,
                "merchant-test",
                providerTradeNo,
                "channel-trade-1",
                payType,
                "TRADE_SUCCESS",
                paidAmountYuan,
                paidAt,
                receivedAt,
                receivedAt.toEpochSecond(),
                "oO1u7d8uvVC8w3fXbDgMDca8gTkO1HLQ_U-HtMxVQ0A",
                "r6J7mFDrb9KH83hLrUkYQgt4AAJwxBkLBzsP4efjEKk");
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
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
