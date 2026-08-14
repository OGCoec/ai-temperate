package com.example.temperate.service.user.apichat;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;

/**
 * 该结果是来绑定严格请求、已启用模型、统一有效输出上限、保守输入估算和客户端 Usage 可见性，后续阶段不得重新解释这些值。
 */
public record ValidatedApiChatRequest(
        ApiChatRequest request,
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedPromptTokens,
        boolean includeUsage) {
}
