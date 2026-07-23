package com.example.temperate.service.registration.verification.delivery.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证 RabbitMQ 延迟重试策略只暴露固定次数和固定延迟，避免运行期出现无限重试。
 */
class VerificationDeliveryRetryPolicyTest {

    @Test
    void exposesFiveRetryDelaysAfterTheInitialAttempt() {
        VerificationDeliveryRetryPolicy policy = VerificationDeliveryRetryPolicy.defaultPolicy();

        assertThat(policy.maxAttempts()).isEqualTo(6);
        assertThat(policy.delayBeforeAttempt(2)).contains(Duration.ofSeconds(10));
        assertThat(policy.delayBeforeAttempt(3)).contains(Duration.ofSeconds(20));
        assertThat(policy.delayBeforeAttempt(4)).contains(Duration.ofSeconds(30));
        assertThat(policy.delayBeforeAttempt(5)).contains(Duration.ofSeconds(60));
        assertThat(policy.delayBeforeAttempt(6)).contains(Duration.ofSeconds(120));
        assertThat(policy.delayBeforeAttempt(7)).isEmpty();
    }
}
