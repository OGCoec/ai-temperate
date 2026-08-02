package com.example.temperate.service.user.aiconversation.billing.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 使用调用时倍率快照计算预扣和实际 minor 额度，并防止缓存输入被普通输入重复计费。
 */
@Component
public final class AiConversationQuotaCalculator {

    private static final BigDecimal WEIGHTED_TOKEN_UNITS_PER_QUOTA =
            BigDecimal.valueOf(80_000L);
    private static final BigDecimal MINOR_UNITS_PER_QUOTA =
            BigDecimal.valueOf(100L);
    private static final long RESERVATION_OUTPUT_DIVISOR = 3L;

    public long reservedQuota(
            long estimatedPromptTokens,
            long maxOutputTokens,
            BigDecimal inputRatio,
            BigDecimal outputRatio) {
        requireNonNegative(estimatedPromptTokens, "estimatedPromptTokens");
        requireNonNegative(maxOutputTokens, "maxOutputTokens");
        // 三分之一只用于降低预扣门槛，不改变上游输出上限或最终实际结算；
        // 使用商和余数向上取整，避免通过 maxOutputTokens 加法实现时发生溢出。
        long reservationOutputTokens = maxOutputTokens
                / RESERVATION_OUTPUT_DIVISOR
                + (maxOutputTokens % RESERVATION_OUTPUT_DIVISOR == 0L
                        ? 0L
                        : 1L);
        return toMinorUnits(
                BigDecimal.valueOf(estimatedPromptTokens).multiply(inputRatio)
                        .add(BigDecimal.valueOf(reservationOutputTokens)
                                .multiply(outputRatio)));
    }

    public long actualQuota(
            long promptTokens,
            long cachedPromptTokens,
            long completionTokens,
            BigDecimal inputRatio,
            BigDecimal cachedInputRatio,
            BigDecimal outputRatio) {
        requireNonNegative(promptTokens, "promptTokens");
        requireNonNegative(cachedPromptTokens, "cachedPromptTokens");
        requireNonNegative(completionTokens, "completionTokens");
        if (cachedPromptTokens > promptTokens) {
            throw new IllegalArgumentException(
                    "cachedPromptTokens must not exceed promptTokens.");
        }
        long uncachedPromptTokens = promptTokens - cachedPromptTokens;
        return toMinorUnits(
                BigDecimal.valueOf(uncachedPromptTokens).multiply(inputRatio)
                        .add(BigDecimal.valueOf(cachedPromptTokens)
                                .multiply(cachedInputRatio))
                        .add(BigDecimal.valueOf(completionTokens)
                                .multiply(outputRatio)));
    }

    private static long toMinorUnits(BigDecimal weightedTokenUnits) {
        // 预扣和结算必须共享同一换算边界，避免退款或补扣使用不同单位；最后一步才向上取整到 minor。
        return weightedTokenUnits
                .multiply(MINOR_UNITS_PER_QUOTA)
                .divide(
                        WEIGHTED_TOKEN_UNITS_PER_QUOTA,
                        0,
                        RoundingMode.CEILING)
                .longValueExact();
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative.");
        }
    }
}
