package com.example.temperate.service.user.membership.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 该配置是来约束会员订单快照跨请求微批的批量、等待和在途上限，防止高并发请求无界进入 Lettuce 命令队列。
 *
 * <p>这些参数只控制应用侧削峰和 Pipeline 组包，不改变 Redis 服务端线程数，也不赋予 Pipeline 事务语义。</p>
 */
@ConfigurationProperties(prefix = "app.membership-payment.redis-write")
public record MembershipPaymentRedisWriteProperties(
        @DefaultValue("64") int batchSize,
        @DefaultValue("6") int laneCount,
        @DefaultValue("PT0.001S") Duration flushWindow,
        @DefaultValue("384") int maximumInflight,
        @DefaultValue("PT30S") Duration submitTimeout,
        @DefaultValue("PT5S") Duration shutdownTimeout) {

    private static final Duration MINIMUM_FLUSH_WINDOW = Duration.ofNanos(100_000L);
    private static final Duration MAXIMUM_FLUSH_WINDOW = Duration.ofMillis(5L);
    private static final Duration MAXIMUM_SUBMIT_TIMEOUT = Duration.ofMinutes(1L);
    private static final Duration MAXIMUM_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30L);

    @ConstructorBinding
    public MembershipPaymentRedisWriteProperties {
        if (batchSize < 1 || batchSize > 192) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write batch size must be between 1 and 192.");
        }
        if (laneCount < 1 || laneCount > 6) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write lane count must be between one and six.");
        }
        if (flushWindow == null
                || flushWindow.compareTo(MINIMUM_FLUSH_WINDOW) < 0
                || flushWindow.compareTo(MAXIMUM_FLUSH_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write flush window must be between 0.1 and 5 milliseconds.");
        }
        if (maximumInflight < batchSize || maximumInflight > 384) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write maximum inflight must be between the batch size and 384.");
        }
        if (submitTimeout == null
                || submitTimeout.isZero()
                || submitTimeout.isNegative()
                || submitTimeout.compareTo(MAXIMUM_SUBMIT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write submit timeout must be positive and at most one minute.");
        }
        if (shutdownTimeout == null
                || shutdownTimeout.isZero()
                || shutdownTimeout.isNegative()
                || shutdownTimeout.compareTo(MAXIMUM_SHUTDOWN_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Membership payment Redis write shutdown timeout must be positive and at most thirty seconds.");
        }
    }
}
