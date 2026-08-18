package com.example.temperate.service.user.aiinference.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 该记录是来冻结一次公开推理预扣的账号、模型倍率和额度，网络阶段只能携带该快照而不能重新读取价格配置。
 */
public record ApiInferenceReservation(
        long usageId,
        long loginIdentityId,
        long apiKeyId,
        long reservedMinor,
        long estimatedInputTokens,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        ApiInferenceProtocol protocol) {

    public ApiInferenceReservation {
        Objects.requireNonNull(inputRatio, "inputRatio");
        Objects.requireNonNull(cachedInputRatio, "cachedInputRatio");
        Objects.requireNonNull(outputRatio, "outputRatio");
        Objects.requireNonNull(protocol, "protocol");
        if (usageId <= 0 || loginIdentityId <= 0 || apiKeyId <= 0
                || reservedMinor < 0 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("API inference reservation is invalid");
        }
    }
}
