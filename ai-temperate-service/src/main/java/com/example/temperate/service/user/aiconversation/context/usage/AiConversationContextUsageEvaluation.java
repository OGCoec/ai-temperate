package com.example.temperate.service.user.aiconversation.context.usage;

import java.math.BigDecimal;

/**
 * 表示一次上下文阈值与模型绝对容量计算的不可变结果。
 */
public record AiConversationContextUsageEvaluation(
        BigDecimal usagePercent,
        boolean thresholdReached,
        boolean hardLimitExceeded) {
}
