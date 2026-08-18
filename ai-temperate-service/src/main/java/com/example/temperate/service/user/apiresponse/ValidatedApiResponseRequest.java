package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该结果是来冻结 Responses 请求的规范模型、流模式、有效输出上限和输入估算，后续上游与计费阶段不得重新解释客户端字段。
 */
public record ValidatedApiResponseRequest(
        ApiResponseRequest request,
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedInputTokens,
        boolean stream,
        ObjectNode normalizedPayload,
        boolean openAiEnhanced) {

    public ValidatedApiResponseRequest(
            ApiResponseRequest request,
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedInputTokens,
            boolean stream) {
        this(request, model, effectiveMaxOutputTokens, estimatedInputTokens,
                stream, null, false);
    }

    public static ValidatedApiResponseRequest openAiEnhanced(
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedInputTokens,
            boolean stream,
            ObjectNode normalizedPayload) {
        return new ValidatedApiResponseRequest(
                null, model, effectiveMaxOutputTokens, estimatedInputTokens,
                stream, normalizedPayload.deepCopy(), true);
    }
}
