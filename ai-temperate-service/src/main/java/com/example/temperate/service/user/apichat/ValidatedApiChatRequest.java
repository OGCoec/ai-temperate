package com.example.temperate.service.user.apichat;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该结果是来绑定请求模式、已启用模型、统一有效输出上限、保守输入估算和客户端 Usage 可见性，后续阶段不得重新解释这些值。
 */
public record ValidatedApiChatRequest(
        ApiChatRequest request,
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedPromptTokens,
        boolean includeUsage,
        boolean stream,
        ObjectNode normalizedPayload,
        OpenAiRequestPayloadMode payloadMode,
        int droppedFieldCount) {

    public ValidatedApiChatRequest {
        payloadMode = Objects.requireNonNull(payloadMode);
        normalizedPayload = normalizedPayload == null ? null : normalizedPayload.deepCopy();
        if (droppedFieldCount < 0) {
            throw new IllegalArgumentException("Dropped field count cannot be negative");
        }
    }

    public ValidatedApiChatRequest(
            ApiChatRequest request,
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedPromptTokens,
            boolean includeUsage) {
        this(request, model, effectiveMaxOutputTokens, estimatedPromptTokens,
                includeUsage, true, null, OpenAiRequestPayloadMode.STRICT_DTO, 0);
    }

    public static ValidatedApiChatRequest compatible(
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedPromptTokens,
            boolean includeUsage,
            boolean stream,
            ObjectNode normalizedPayload,
            OpenAiRequestPayloadMode payloadMode,
            int droppedFieldCount) {
        return new ValidatedApiChatRequest(
                null, model, effectiveMaxOutputTokens, estimatedPromptTokens,
                includeUsage, stream, normalizedPayload, payloadMode, droppedFieldCount);
    }

    @Override
    public ObjectNode normalizedPayload() {
        return normalizedPayload == null ? null : normalizedPayload.deepCopy();
    }
}
