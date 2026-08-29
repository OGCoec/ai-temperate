package com.example.temperate.service.user.membership.payment.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteOutcome;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束会员支付 Redis Hash 以 epoch-micros 保存业务时间，禁止在异步落库前退化为毫秒。
 */
final class MembershipPaymentRedisCodecTest {

    private static final OffsetDateTime TIME =
            OffsetDateTime.parse("2026-08-23T18:22:00.573421Z");

    @Test
    void callbackHashRoundTripPreservesMicroseconds() {
        PaymentCallbackSnapshot callback = new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                id((byte) 2),
                id((byte) 1),
                "merchant-test",
                "provider-trade-1",
                "channel-trade-1",
                "alipay",
                "TRADE_SUCCESS",
                new BigDecimal("20.00"),
                TIME,
                TIME.plusNanos(123_000L),
                TIME.toEpochSecond(),
                "a".repeat(43),
                "b".repeat(43));

        Map<String, String> fields = MembershipPaymentRedisCodec.writeCallback(callback);
        PaymentCallbackSnapshot decoded = MembershipPaymentRedisCodec.readCallback(fields);

        assertThat(fields.get("paidAt")).isEqualTo("1787509320573421");
        assertThat(fields.get("receivedAt")).isEqualTo("1787509320573544");
        assertThat(decoded.paidAt()).isEqualTo(TIME);
        assertThat(decoded.receivedAt()).isEqualTo(TIME.plusNanos(123_000L));
    }

    @Test
    void providerHashUsesTheSameMicrosecondContract() {
        SimulatedPaymentProviderResult provider = new SimulatedPaymentProviderResult(
                SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                id((byte) 1),
                SimulatedPaymentProviderStatus.PAID,
                id((byte) 2),
                "provider-trade-1",
                "alipay",
                new BigDecimal("20.00"),
                TIME);

        Map<String, String> fields = MembershipPaymentRedisCodec.writeProvider(provider);

        assertThat(fields.get("updatedAt")).isEqualTo("1787509320573421");
        assertThat(MembershipPaymentRedisCodec.readProvider(fields).updatedAt()).isEqualTo(TIME);
    }

    @Test
    void commonWriteOutcomesReuseTheCommittedSnapshotButConflictsRequireRedisFields() {
        MembershipOrderSnapshot submitted = order();

        for (String outcome : List.of("CREATED", "REPLACED", "APPLIED", "UNCHANGED")) {
            assertThat(MembershipPaymentRedisCodec.readOrderWriteReply(
                            List.of(outcome), submitted))
                    .satisfies(result -> {
                        assertThat(result.outcome().name()).isEqualTo(outcome);
                        assertThat(result.snapshot()).isSameAs(submitted);
                    });
        }
        assertThat(MembershipPaymentRedisCodec.readOrderWriteReply(
                        List.of("MISSING"), submitted))
                .satisfies(result -> {
                    assertThat(result.outcome())
                            .isEqualTo(MembershipOrderSnapshotWriteOutcome.MISSING);
                    assertThat(result.snapshot()).isNull();
                });
        assertThat(MembershipPaymentRedisCodec.readOrderWriteReply(
                        List.of("REQUIRES_RESTORE"), submitted))
                .satisfies(result -> assertThat(result.snapshot()).isNull());
        assertThatThrownBy(() -> MembershipPaymentRedisCodec.readOrderWriteReply(
                        List.of("STALE"), submitted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
        assertThatThrownBy(() -> MembershipPaymentRedisCodec.readOrderWriteReply(
                        List.of("CONFLICT"), submitted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields");
    }

    private static MembershipOrderSnapshot order() {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                id((byte) 9),
                9L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PENDING_PAYMENT,
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                null,
                null,
                TIME.plusMinutes(5),
                null,
                null,
                1L,
                TIME,
                TIME);
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
