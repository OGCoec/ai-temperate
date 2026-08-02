package com.example.temperate.service.user.aiconversation.generation;

/**
 * 表示 PostgreSQL 已提交后需要向 RabbitMQ 发布的初始 Generation 调度事实。
 */
public record AiConversationGenerationDispatchEvent(
        String generationPublicId,
        String usagePublicId,
        String traceId) {
}
