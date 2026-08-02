package com.example.temperate.service.user.aiconversation.context;

/**
 * 定义文字、图片、压缩摘要和历史轮次在调用前的保守 Token 估算边界。
 */
public interface AiConversationTokenEstimator {

    long estimate(
            String systemPrompt,
            String durableCompactionJson,
            String ephemeralCompactionJson,
            java.util.List<AiConversationTurn> turns,
            AiConversationContent currentInput);
}
