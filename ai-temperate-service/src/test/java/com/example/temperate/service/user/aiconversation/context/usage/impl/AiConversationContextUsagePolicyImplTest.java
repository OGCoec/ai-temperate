package com.example.temperate.service.user.aiconversation.context.usage.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageEvaluation;
import org.junit.jupiter.api.Test;

/**
 * 验证上下文预压缩阈值和模型绝对容量使用彼此独立的判定边界。
 */
final class AiConversationContextUsagePolicyImplTest {

    private final AiConversationContextUsagePolicyImpl policy =
            new AiConversationContextUsagePolicyImpl(80);

    @Test
    void exactEightyPercentTriggersCompaction() {
        AiConversationContextUsageEvaluation evaluation = policy.evaluate(
                80_000L, 80_000L, 100_000L, 10_000L);

        assertThat(evaluation.thresholdReached()).isTrue();
        assertThat(evaluation.usagePercent()).isEqualByComparingTo("80.0");
        assertThat(evaluation.hardLimitExceeded()).isFalse();
    }

    @Test
    void usageBelowEightyPercentDoesNotTriggerCompaction() {
        AiConversationContextUsageEvaluation evaluation = policy.evaluate(
                79_900L, 79_900L, 100_000L, 10_000L);

        assertThat(evaluation.thresholdReached()).isFalse();
        assertThat(evaluation.usagePercent()).isEqualByComparingTo("79.9");
    }

    @Test
    void hardLimitUsesPromptPlusReservedOutputInsteadOfPreCompactionThreshold() {
        assertThat(policy.evaluate(
                85_000L, 85_000L, 100_000L, 10_000L)
                .hardLimitExceeded()).isFalse();
        assertThat(policy.evaluate(
                85_000L, 95_000L, 100_000L, 10_000L)
                .hardLimitExceeded()).isTrue();
    }
}
