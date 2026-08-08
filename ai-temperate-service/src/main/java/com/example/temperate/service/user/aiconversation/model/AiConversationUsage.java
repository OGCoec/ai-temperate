package com.example.temperate.service.user.aiconversation.model;

/**
 * 表示上游最终返回的普通输入、缓存输入、输出和思考 Token 权威统计。
 */
public record AiConversationUsage(
        long promptTokens,
        long cachedPromptTokens,
        long completionTokens,
        long reasoningTokens) implements AiConversationMeteredUsage {

    public AiConversationUsage {
        if (promptTokens < 0
                || cachedPromptTokens < 0
                || completionTokens < 0
                || reasoningTokens < 0
                || cachedPromptTokens > promptTokens) {
            throw new IllegalArgumentException("AI usage values are invalid.");
        }
    }

    @Override
    public AiConversationMeteringBasis basis() {
        return AiConversationMeteringBasis.TOKEN;
    }
}
