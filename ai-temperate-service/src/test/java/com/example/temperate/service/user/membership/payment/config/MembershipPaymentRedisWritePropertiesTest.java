package com.example.temperate.service.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 该配置测试是来锁定会员订单 Redis 微批的 64 条 Pipeline、六 lane 与 384 条在途写入边界。
 */
final class MembershipPaymentRedisWritePropertiesTest {

    @Test
    void productionBoundaryAcceptsSixLightweightPipelinesInFlight() {
        MembershipPaymentRedisWriteProperties properties = properties(64, 384, 6);

        assertThat(properties.batchSize()).isEqualTo(64);
        assertThat(properties.laneCount()).isEqualTo(6);
        assertThat(properties.maximumInflight()).isEqualTo(384);
        assertThat(properties.flushWindow()).isEqualTo(Duration.ofMillis(1));
        assertThat(properties.submitTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void laneCountAcceptsOneThroughSixBoundedPipelines() {
        assertThatCode(() -> properties(64, 384, 1)).doesNotThrowAnyException();
        assertThatCode(() -> properties(64, 384, 6)).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties(64, 384, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and six");
        assertThatThrownBy(() -> properties(64, 384, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and six");
    }

    @Test
    void batchSizeAcceptsOneAndOneHundredNinetyTwoOnly() {
        assertThatCode(() -> properties(1, 384)).doesNotThrowAnyException();
        assertThatCode(() -> properties(192, 384)).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties(0, 384))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 192");
        assertThatThrownBy(() -> properties(193, 384))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 192");
    }

    @Test
    void inflightMustFitAtLeastOneWholeBatchAndRemainBounded() {
        assertThatThrownBy(() -> properties(192, 191))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between the batch size and 384");
        assertThatThrownBy(() -> properties(192, 385))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between the batch size and 384");
    }

    @Test
    void flushAndTimeoutBoundariesRemainStrictlyBounded() {
        assertThatCode(() -> new MembershipPaymentRedisWriteProperties(
                        192,
                        2,
                        Duration.ofNanos(100_000),
                        384,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new MembershipPaymentRedisWriteProperties(
                        192,
                        2,
                        Duration.ofNanos(99_999),
                        384,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MembershipPaymentRedisWriteProperties(
                        192,
                        2,
                        Duration.ofMillis(1),
                        384,
                        Duration.ofMinutes(1).plusNanos(1),
                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MembershipPaymentRedisWriteProperties properties(
            int batchSize,
            int maximumInflight) {
        return properties(batchSize, maximumInflight, 2);
    }

    private static MembershipPaymentRedisWriteProperties properties(
            int batchSize,
            int maximumInflight,
            int laneCount) {
        return new MembershipPaymentRedisWriteProperties(
                batchSize,
                laneCount,
                Duration.ofMillis(1),
                maximumInflight,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
    }
}
