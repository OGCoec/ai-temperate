package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

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
        OpenAiRequestPayloadMode payloadMode,
        int droppedFieldCount) {

    public ValidatedApiResponseRequest {
        payloadMode = Objects.requireNonNull(payloadMode);
        normalizedPayload = normalizedPayload == null ? null : normalizedPayload.deepCopy();
        if (droppedFieldCount < 0) {
            throw new IllegalArgumentException("Dropped field count cannot be negative");
        }
    }

    public ValidatedApiResponseRequest(
            ApiResponseRequest request,
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedInputTokens,
            boolean stream) {
        this(request, model, effectiveMaxOutputTokens, estimatedInputTokens,
                stream, null, OpenAiRequestPayloadMode.STRICT_DTO, 0);
    }

    public static ValidatedApiResponseRequest compatible(
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedInputTokens,
            boolean stream,
            ObjectNode normalizedPayload,
            OpenAiRequestPayloadMode payloadMode,
            int droppedFieldCount) {
        return new ValidatedApiResponseRequest(
                null, model, effectiveMaxOutputTokens, estimatedInputTokens,
                stream, normalizedPayload, payloadMode, droppedFieldCount);
    }

    @Override
    public ObjectNode normalizedPayload() {
        return normalizedPayload == null ? null : normalizedPayload.deepCopy();
    }
}
