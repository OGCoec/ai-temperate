package com.example.temperate.service.auth.login.audit.observer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.auth.login.audit.enums.LoginAuditOutcome;
import com.example.temperate.service.auth.login.audit.enums.LoginAuditReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 验证登录审计观测器仅使用受控结果和原因标签生成指标。
 */
class MicrometerLoginAuditObserverTest {

    @Test
    void recordsOnlyLowCardinalityOutcomeAndReasonTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLoginAuditObserver observer = new MicrometerLoginAuditObserver(registry);

        observer.observe(LoginAuditOutcome.SUCCESS, LoginAuditReason.AUTHENTICATED);

        Counter counter = registry.find("auth.login.attempts")
                .tags("outcome", "SUCCESS", "reason", "AUTHENTICATED")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
        Map<String, String> tags = counter.getId().getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
        assertThat(tags).containsOnly(
                Map.entry("outcome", "SUCCESS"),
                Map.entry("reason", "AUTHENTICATED"));
        assertThat(tags.toString()).doesNotContain(
                "email", "phone", "token", "redis", "device", "203.0.113.10");
    }

    @Test
    void rejectsMissingBoundedLabels() {
        MicrometerLoginAuditObserver observer =
                new MicrometerLoginAuditObserver(new SimpleMeterRegistry());

        assertThatThrownBy(() -> observer.observe(null, LoginAuditReason.AUTHENTICATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> observer.observe(LoginAuditOutcome.SUCCESS, null))
                .isInstanceOf(NullPointerException.class);
    }
}
