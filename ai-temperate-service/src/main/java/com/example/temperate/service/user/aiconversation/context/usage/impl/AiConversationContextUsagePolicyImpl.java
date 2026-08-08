package com.example.temperate.service.user.aiconversation.context.usage.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageEvaluation;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsagePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 使用整数交叉相乘判定阈值，并只在展示阶段执行一位小数舍入。
 */
@Service
public final class AiConversationContextUsagePolicyImpl
        implements AiConversationContextUsagePolicy {

    private final int thresholdPercent;

    @Autowired
    public AiConversationContextUsagePolicyImpl(
            AiConversationProperties properties) {
        this(Objects.requireNonNull(properties).preCompactionPercent());
    }

    AiConversationContextUsagePolicyImpl(int thresholdPercent) {
        if (thresholdPercent < 1 || thresholdPercent > 99) {
            throw new IllegalArgumentException(
                    "AI context threshold percent is invalid.");
        }
        this.thresholdPercent = thresholdPercent;
    }

    @Override
    public AiConversationContextUsageEvaluation evaluate(
            long estimatedContextTokens,
            long estimatedPromptTokens,
            long contextWindowTokens,
            long maxOutputTokens) {
        if (estimatedContextTokens < 0L
                || estimatedPromptTokens < 0L
                || contextWindowTokens <= 0L
                || maxOutputTokens <= 0L) {
            throw new IllegalArgumentException(
                    "AI context usage inputs are invalid.");
        }
        boolean thresholdReached = Math.multiplyExact(
                estimatedContextTokens, 100L)
                >= Math.multiplyExact(contextWindowTokens, thresholdPercent);
        boolean hardLimitExceeded = Math.addExact(
                estimatedPromptTokens, maxOutputTokens) > contextWindowTokens;
        BigDecimal percentage = BigDecimal.valueOf(estimatedContextTokens)
                .multiply(BigDecimal.valueOf(100L))
                .divide(
                        BigDecimal.valueOf(contextWindowTokens),
                        1,
                        RoundingMode.HALF_UP);
        return new AiConversationContextUsageEvaluation(
                percentage, thresholdReached, hardLimitExceeded);
    }
}
