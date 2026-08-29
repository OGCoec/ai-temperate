package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束会员支付耗时观测的采样率和慢请求阈值，防止非法配置在高并发链路中制造无界日志。
 */
final class MembershipPaymentObservabilityPropertiesTest {

    @Test
    void acceptsSafeProductionDefaults() {
        MembershipPaymentObservabilityProperties properties =
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0.01D,
                        Duration.ofSeconds(1),
                        Set.of(MembershipPaymentOperation.ORDER_CREATE),
                        "boundary-20260824-01",
                        true);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.detailLogEnabled()).isFalse();
        assertThat(properties.sampleRate()).isEqualTo(0.01D);
        assertThat(properties.slowThreshold()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.forceLogOperations())
                .containsExactly(MembershipPaymentOperation.ORDER_CREATE);
        assertThat(properties.runId()).isEqualTo("boundary-20260824-01");
        assertThat(properties.includePublicOrderId()).isTrue();
    }

    @Test
    void rejectsOutOfRangeSampleRate() {
        assertThatThrownBy(() -> new MembershipPaymentObservabilityProperties(
                        true, false, 1.01D, Duration.ofSeconds(1), "unavailable", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSlowThreshold() {
        assertThatThrownBy(() -> new MembershipPaymentObservabilityProperties(
                        true, false, 0.01D, Duration.ZERO, "unavailable", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRunIdThatCannotBeSafelyLogged() {
        assertThatThrownBy(() -> new MembershipPaymentObservabilityProperties(
                        true, false, 0.01D, Duration.ofSeconds(1), "bad run id", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
