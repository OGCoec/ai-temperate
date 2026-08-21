package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 该测试是来锁定 AT-only 压测开关与 fast/realtime 时间配置只能在白名单 Profile 中启动的安全边界。
 */
final class MembershipPaymentLoadtestProfileGuardTest {

    @Test
    void enabledLoadtestIsRejectedInProductionProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new MembershipPaymentLoadtestProfileGuard(
                        loadtest(true), realtimeProperties(), environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Profile");
    }

    @Test
    void fastTimingRequiresFastProfileAndEnabledSwitch() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-dev");

        assertThatThrownBy(() -> new MembershipPaymentLoadtestProfileGuard(
                        loadtest(false), fastProperties(), environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loadtest-fast");
    }

    @Test
    void enabledFastContractIsAcceptedForAllowedProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-dev", "loadtest-fast");

        assertThatCode(() -> new MembershipPaymentLoadtestProfileGuard(
                        loadtest(true), fastProperties(), environment).validate())
                .doesNotThrowAnyException();
    }

    private static MembershipPaymentLoadtestProperties loadtest(boolean enabled) {
        return new MembershipPaymentLoadtestProperties(enabled, List.of(73014701344296960L));
    }

    private static MembershipPaymentProperties realtimeProperties() {
        return properties(
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                        30_000L, 30_000L, 60_000L, 120_000L),
                List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L));
    }

    private static MembershipPaymentProperties fastProperties() {
        return properties(
                Duration.ofSeconds(45),
                Duration.ofSeconds(40),
                List.of(10_000L, 10_000L, 10_000L, 15_000L),
                List.of(10_000L, 15_000L, 15_000L));
    }

    private static MembershipPaymentProperties properties(
            Duration pending,
            Duration closing,
            List<Long> pendingDelays,
            List<Long> closingDelays) {
        return new MembershipPaymentProperties(
                true,
                pending,
                closing,
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        pendingDelays, closingDelays, Duration.ofSeconds(30), 3));
    }
}
