package com.example.temperate.service.user.apichat;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该结果是来绑定严格请求、已启用模型、统一有效输出上限、保守输入估算和客户端 Usage 可见性，后续阶段不得重新解释这些值。
 */
public record ValidatedApiChatRequest(
        ApiChatRequest request,
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedPromptTokens,
        boolean includeUsage,
        boolean stream,
        ObjectNode normalizedPayload,
        boolean openAiEnhanced) {

    public ValidatedApiChatRequest(
            ApiChatRequest request,
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedPromptTokens,
            boolean includeUsage) {
        this(request, model, effectiveMaxOutputTokens, estimatedPromptTokens,
                includeUsage, true, null, false);
    }

    public static ValidatedApiChatRequest openAiEnhanced(
            AiModelCacheEntry model,
            long effectiveMaxOutputTokens,
            long estimatedPromptTokens,
            boolean includeUsage,
            boolean stream,
            ObjectNode normalizedPayload) {
        return new ValidatedApiChatRequest(
                null, model, effectiveMaxOutputTokens, estimatedPromptTokens,
                includeUsage, stream, normalizedPayload.deepCopy(), true);
    }
}
