package com.example.temperate.service.user.aiconversation.context.usage;

/**
 * 定义预压缩阈值、展示百分比和模型绝对容量的统一计算边界。
 */
public interface AiConversationContextUsagePolicy {

    AiConversationContextUsageEvaluation evaluate(
            long estimatedContextTokens,
            long estimatedPromptTokens,
            long contextWindowTokens,
            long maxOutputTokens);
}
