package com.example.temperate.service.user.aiinference.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 该记录是来冻结一次公开推理预扣的账号、模型倍率和额度，网络阶段只能携带该快照而不能重新读取价格配置。
 */
public record ApiInferenceReservation(
        byte[] usageId,
        long loginIdentityId,
        byte[] apiKeyId,
        long reservedMinor,
        long estimatedInputTokens,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        ApiInferenceProtocol protocol) {

    public ApiInferenceReservation {
        usageId = Objects.requireNonNull(usageId, "usageId").clone();
        apiKeyId = Objects.requireNonNull(apiKeyId, "apiKeyId").clone();
        Objects.requireNonNull(inputRatio, "inputRatio");
        Objects.requireNonNull(cachedInputRatio, "cachedInputRatio");
        Objects.requireNonNull(outputRatio, "outputRatio");
        Objects.requireNonNull(protocol, "protocol");
        if (usageId.length != 16 || loginIdentityId <= 0 || apiKeyId.length != 16
                || reservedMinor < 0 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("API inference reservation is invalid");
        }
    }

    @Override
    public byte[] usageId() {
        return usageId.clone();
    }

    @Override
    public byte[] apiKeyId() {
        return apiKeyId.clone();
    }
}
