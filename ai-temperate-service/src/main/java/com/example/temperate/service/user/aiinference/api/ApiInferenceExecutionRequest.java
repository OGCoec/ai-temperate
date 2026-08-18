package com.example.temperate.service.user.aiinference.api;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import java.util.Objects;

/**
 * 该记录是来冻结一次公开模型调用进入并发和预扣阶段所需的协议中立参数，协议层完成校验后不得重新解释这些值。
 */
public record ApiInferenceExecutionRequest(
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedInputTokens,
        boolean stream,
        ApiInferenceProtocol protocol) {

    public ApiInferenceExecutionRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(protocol, "protocol");
        if (effectiveMaxOutputTokens <= 0 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("API inference token bounds are invalid");
        }
    }
}
