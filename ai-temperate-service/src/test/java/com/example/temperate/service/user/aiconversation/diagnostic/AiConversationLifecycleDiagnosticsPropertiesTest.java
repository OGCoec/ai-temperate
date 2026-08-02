package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiConversationLifecycleDiagnosticsProperties;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 会话生命周期诊断默认关闭，并只对稳定采样命中的安全关联标识输出日志。
 */
final class AiConversationLifecycleDiagnosticsPropertiesTest {

    @Test
    void disabledDiagnosticsNeverSampleRequests() {
        AiConversationLifecycleDiagnosticsProperties properties =
                new AiConversationLifecycleDiagnosticsProperties(false, 1.0d);

        assertThat(properties.shouldSample("client-request-id", "usage-public-id"))
                .isFalse();
    }

    @Test
    void fullSamplingAcceptsClientOrUsageCorrelation() {
        AiConversationLifecycleDiagnosticsProperties properties =
                new AiConversationLifecycleDiagnosticsProperties(true, 1.0d);

        assertThat(properties.shouldSample("client-request-id", "unavailable"))
                .isTrue();
        assertThat(properties.shouldSample("unavailable", "usage-public-id"))
                .isTrue();
    }

    @Test
    void missingCorrelationIsNotSampledBelowFullRate() {
        AiConversationLifecycleDiagnosticsProperties properties =
                new AiConversationLifecycleDiagnosticsProperties(true, 0.5d);

        assertThat(properties.shouldSample("unavailable", "unavailable"))
                .isFalse();
    }
}
