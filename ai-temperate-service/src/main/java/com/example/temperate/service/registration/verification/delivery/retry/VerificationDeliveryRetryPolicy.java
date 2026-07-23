package com.example.temperate.service.registration.verification.delivery.retry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 定义验证码投递进入 RabbitMQ 延迟重试时允许使用的固定尝试次数和延迟表。
 *
 * <p>该策略把首次立即发送也计入总次数，避免消费者在异常路径下推导出无限重试或与验证码 TTL 冲突的重试计划。</p>
 */
public final class VerificationDeliveryRetryPolicy {

    private static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120));

    private final List<Duration> retryDelays;

    private VerificationDeliveryRetryPolicy(List<Duration> retryDelays) {
        this.retryDelays = List.copyOf(retryDelays);
    }

    public static VerificationDeliveryRetryPolicy defaultPolicy() {
        return new VerificationDeliveryRetryPolicy(DEFAULT_RETRY_DELAYS);
    }

    public int maxAttempts() {
        return retryDelays.size() + 1;
    }

    public Optional<Duration> delayBeforeAttempt(int attemptNo) {
        int retryIndex = attemptNo - 2;
        if (retryIndex < 0 || retryIndex >= retryDelays.size()) {
            return Optional.empty();
        }
        return Optional.of(retryDelays.get(retryIndex));
    }
}
