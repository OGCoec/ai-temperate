package com.example.temperate.service.user.aiconversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证流式时序诊断配置的关闭默认值、采样边界和窗口约束。
 */
final class AiConversationStreamDiagnosticsPropertiesTest {

    @Test
    void disabledConfigurationDoesNotSampleAnyUsage() {
        AiConversationStreamDiagnosticsProperties properties = properties(
                false, 0.0d);

        assertThat(properties.shouldSample("AZ-50wCZAQGBuCvbSqIYsA"))
                .isFalse();
    }

    @Test
    void fullSamplingUsesEveryValidUsagePublicId() {
        AiConversationStreamDiagnosticsProperties properties = properties(
                true, 1.0d);

        assertThat(properties.shouldSample("AZ-50wCZAQGBuCvbSqIYsA"))
                .isTrue();
        assertThat(properties.shouldSample("AZ-5sTKNAQGQIaW7uaggjw"))
                .isTrue();
    }

    @Test
    void stableSamplingReturnsTheSameDecisionForOneUsage() {
        AiConversationStreamDiagnosticsProperties properties = properties(
                true, 0.5d);

        boolean first = properties.shouldSample("AZ-50wCZAQGBuCvbSqIYsA");

        assertThat(properties.shouldSample("AZ-50wCZAQGBuCvbSqIYsA"))
                .isEqualTo(first);
    }

    @Test
    void rejectsInvalidSamplingAndWindowValues() {
        assertThatThrownBy(() -> new AiConversationStreamDiagnosticsProperties(
                true,
                1.01d,
                Duration.ofSeconds(1),
                100,
                Duration.ofSeconds(5),
                Duration.ofMillis(250),
                200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiConversationStreamDiagnosticsProperties(
                true,
                1.0d,
                Duration.ZERO,
                100,
                Duration.ofSeconds(5),
                Duration.ofMillis(250),
                200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiConversationStreamDiagnosticsProperties properties(
            boolean enabled,
            double sampleRate) {
        return new AiConversationStreamDiagnosticsProperties(
                enabled,
                sampleRate,
                Duration.ofSeconds(1),
                100,
                Duration.ofSeconds(5),
                Duration.ofMillis(250),
                200);
    }
}
