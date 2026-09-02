package com.example.temperate.service.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该配置测试是来锁定会员支付总开关、模拟器密钥、批次上限和两段精确五分钟延时的启动失败边界。
 */
final class MembershipPaymentPropertiesTest {

    @Test
    void simulatorCannotBeEnabledWhileMembershipPaymentIsDisabled() {
        assertThatThrownBy(() -> properties(false, true, validRabbit(), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simulator");
    }

    @Test
    void delayPlanMustTotalExactlyFiveMinutes() {
        MembershipPaymentProperties.Rabbit invalid =
                new MembershipPaymentProperties.Rabbit(
                        List.of(299_999L),
                        List.of(300_000L),
                        Duration.ofSeconds(30),
                        3);

        assertThatThrownBy(() -> properties(true, false, invalid, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("five minutes");
    }

    @Test
    void fastLoadtestTimingContractIsAcceptedAsAWhole() {
        MembershipPaymentProperties.Rabbit fastRabbit =
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L),
                        List.of(10_000L, 15_000L, 15_000L),
                        Duration.ofSeconds(30),
                        3);

        assertThatCode(() -> properties(
                        true,
                        false,
                        fastRabbit,
                        100,
                        Duration.ofSeconds(45),
                        Duration.ofSeconds(40)))
                .doesNotThrowAnyException();
    }

    @Test
    void arbitraryShortTimingCannotMasqueradeAsLoadtestFast() {
        MembershipPaymentProperties.Rabbit arbitraryRabbit =
                new MembershipPaymentProperties.Rabbit(
                        List.of(20_000L),
                        List.of(20_000L),
                        Duration.ofSeconds(30),
                        3);

        assertThatThrownBy(() -> properties(
                        true,
                        false,
                        arbitraryRabbit,
                        100,
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timing contract");
    }

    @Test
    void refundRetryPlanMustUseFixedTimeoutOnlySchedule() {
        MembershipPaymentProperties.Rabbit invalid =
                new MembershipPaymentProperties.Rabbit(
                        validRabbit().paymentCheckDelaysMillis(),
                        validRabbit().closingCheckDelaysMillis(),
                        List.of(5_000L, 10_000L),
                        Duration.ofSeconds(30),
                        3);

        assertThatThrownBy(() -> properties(true, false, invalid, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund retry");
    }

    @Test
    void callbackBatchCannotExceedRedisBatchBoundary() {
        assertThatThrownBy(() -> properties(true, false, validRabbit(), 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 500");
    }

    @Test
    void orderPersistBatchCannotExceedCrossKeyCompletionBoundary() {
        assertThatThrownBy(() -> propertiesWithOrderPersistBatch(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
    }

    @Test
    void callbackSafetyTtlsCannotDriftFromTheClosureContract() {
        MembershipPaymentProperties.Callback changed =
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(31),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6));

        assertThatThrownBy(() -> properties(true, false, validRabbit(), changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback dedupe TTL");
    }

    @Test
    void callbackDataTtlMustRemainSixHours() {
        MembershipPaymentProperties.Callback changed =
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(24));

        assertThatThrownBy(() -> properties(true, false, validRabbit(), changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback data TTL");
    }

    @Test
    void liuhaoConfigurationDoesNotInventAProviderChannelId() {
        assertThatCode(() -> propertiesWithLiuhao(liuhao(true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> propertiesWithLiuhao(liuhao(false)))
                .doesNotThrowAnyException();
    }

    private static MembershipPaymentProperties properties(
            boolean enabled,
            boolean simulatorEnabled,
            MembershipPaymentProperties.Rabbit rabbit,
            int callbackBatchSize) {
        return properties(
                enabled,
                simulatorEnabled,
                rabbit,
                callbackBatchSize,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5));
    }

    private static MembershipPaymentProperties properties(
            boolean enabled,
            boolean simulatorEnabled,
            MembershipPaymentProperties.Rabbit rabbit,
            int callbackBatchSize,
            Duration pendingDuration,
            Duration closingDuration) {
        return properties(
                enabled,
                simulatorEnabled,
                rabbit,
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        callbackBatchSize,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                pendingDuration,
                closingDuration);
    }

    private static MembershipPaymentProperties properties(
            boolean enabled,
            boolean simulatorEnabled,
            MembershipPaymentProperties.Rabbit rabbit,
            MembershipPaymentProperties.Callback callback) {
        return properties(
                enabled,
                simulatorEnabled,
                rabbit,
                callback,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5));
    }

    private static MembershipPaymentProperties properties(
            boolean enabled,
            boolean simulatorEnabled,
            MembershipPaymentProperties.Rabbit rabbit,
            MembershipPaymentProperties.Callback callback,
            Duration pendingDuration,
            Duration closingDuration) {
        return new MembershipPaymentProperties(
                enabled,
                pendingDuration,
                closingDuration,
                new MembershipPaymentProperties.Simulator(
                        simulatorEnabled,
                        simulatorEnabled ? "merchant-test" : "",
                        simulatorEnabled
                                ? "0123456789abcdef0123456789abcdef"
                                : "",
                        Duration.ofMinutes(5),
                        16_384, false),
                callback,
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                rabbit);
    }

    private static MembershipPaymentProperties propertiesWithOrderPersistBatch(
            int orderPersistBatchSize) {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false,
                        "",
                        "",
                        Duration.ofMinutes(5),
                        16_384,
                        false),
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        orderPersistBatchSize,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                validRabbit());
    }

    private static MembershipPaymentProperties propertiesWithLiuhao(
            MembershipPaymentProperties.Liuhao liuhao) {
        return new MembershipPaymentProperties(
                liuhao.enabled(),
                liuhao.enabled(),
                PaymentProviderType.LIUHAO,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false,
                        "",
                        "",
                        Duration.ofMinutes(5),
                        16_384,
                        false),
                new MembershipPaymentProperties.Bar(
                        false,
                        URI.create("https://ihaveagoddamnplan.com"),
                        "",
                        0,
                        Map.of(),
                        null,
                        null,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        65_536),
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                validRabbit(),
                liuhao,
                List.of(PaymentProviderType.LIUHAO));
    }

    private static MembershipPaymentProperties.Liuhao liuhao(boolean enabled) {
        return new MembershipPaymentProperties.Liuhao(
                enabled,
                URI.create("https://liuhao.net"),
                enabled ? "1001" : "",
                enabled ? "merchant-private-key" : "",
                enabled ? "platform-public-key" : "",
                enabled ? "merchant-public-key" : "",
                enabled ? URI.create("https://niko000o.site/api/payment/liuhao/notify") : null,
                enabled ? URI.create("https://niko000o.site/pages/account/payment-result") : null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65_536,
                Duration.ofMinutes(5));
    }

    private static MembershipPaymentProperties.Rabbit validRabbit() {
        return new MembershipPaymentProperties.Rabbit(
                List.of(
                        10_000L,
                        10_000L,
                        10_000L,
                        15_000L,
                        15_000L,
                        30_000L,
                        30_000L,
                        60_000L,
                        120_000L),
                List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                Duration.ofSeconds(30),
                3);
    }
}
