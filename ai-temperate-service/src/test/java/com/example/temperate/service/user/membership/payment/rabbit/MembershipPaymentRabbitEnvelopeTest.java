package com.example.temperate.service.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来约束会员支付 Rabbit 信封只接受可安全写入诊断日志的 Trace 标识，防止异步消息伪造日志行。
 */
final class MembershipPaymentRabbitEnvelopeTest {

    @Test
    void rejectsTraceIdContainingLineBreak() {
        assertThatThrownBy(() -> new MembershipPaymentRabbitEnvelope<>(
                id((byte) 7),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-ok\nforged-log-line",
                new MembershipPaymentCheckMessage(id((byte) 6), 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership payment trace ID is invalid.");
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
