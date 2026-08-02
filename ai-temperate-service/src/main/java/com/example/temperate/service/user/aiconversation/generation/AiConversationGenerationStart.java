package com.example.temperate.service.user.aiconversation.generation;

/**
 * 返回异步 Generation 创建或幂等重放后的公共资源标识，供首个 SSE Observer 建立关联。
 */
public record AiConversationGenerationStart(
        String generationPublicId,
        String conversationPublicId,
        String usagePublicId,
        String modelPublicId,
        boolean newConversation,
        boolean replay) {
}
