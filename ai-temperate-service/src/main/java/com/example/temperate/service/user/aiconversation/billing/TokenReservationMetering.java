package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 冻结 Token 请求预扣时使用的本地输入估算、最大输出和三类倍率快照。
 */
public record TokenReservationMetering(
        long estimatedPromptTokens,
        long maxOutputTokens,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio) implements AiConversationReservationMetering {

    public TokenReservationMetering {
        if (estimatedPromptTokens < 0L || maxOutputTokens <= 0L) {
            throw new IllegalArgumentException(
                    "Token reservation limits are invalid.");
        }
        inputRatio = requireRatio(inputRatio);
        cachedInputRatio = requireRatio(cachedInputRatio);
        outputRatio = requireRatio(outputRatio);
    }

    @Override
    public AiConversationMeteringBasis basis() {
        return AiConversationMeteringBasis.TOKEN;
    }

    private static BigDecimal requireRatio(BigDecimal ratio) {
        Objects.requireNonNull(ratio);
        if (ratio.signum() < 0) {
            throw new IllegalArgumentException("Token ratio must be non-negative.");
        }
        return ratio;
    }
}
