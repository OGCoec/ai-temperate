package com.example.temperate.service.user.membership.payment.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束会员支付时间统一转换为 UTC 微秒，并保证 Redis epoch-micros 往返不丢失精度。
 */
final class MembershipPaymentTimeTest {

    @Test
    void normalizesClockAndExternalTimeToUtcMicroseconds() {
        Instant source = Instant.parse("2026-08-23T18:22:00.573421999Z");

        OffsetDateTime captured = MembershipPaymentTime.now(
                Clock.fixed(source, ZoneOffset.UTC));
        OffsetDateTime normalized = MembershipPaymentTime.normalize(
                OffsetDateTime.parse("2026-08-23T13:22:00.573421999-05:00"));

        assertThat(captured.toString()).isEqualTo("2026-08-23T18:22:00.573421Z");
        assertThat(normalized).isEqualTo(captured);
    }

    @Test
    void convertsEpochMicrosecondsWithoutFallingBackToMilliseconds() {
        OffsetDateTime value = OffsetDateTime.parse("2026-08-23T18:22:00.573421Z");

        long encoded = MembershipPaymentTime.toEpochMicros(value);
        OffsetDateTime decoded = MembershipPaymentTime.fromEpochMicros(encoded);

        assertThat(encoded).isEqualTo(1_787_509_320_573_421L);
        assertThat(decoded).isEqualTo(value);
    }
}
