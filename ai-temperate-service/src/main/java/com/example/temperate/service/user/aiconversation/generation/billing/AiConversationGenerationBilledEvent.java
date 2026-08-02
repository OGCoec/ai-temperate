package com.example.temperate.service.user.aiconversation.generation.billing;

/**
 * 表示 Generation 与资金终态已经在 PostgreSQL 提交，可更新 Redis 展示终态并让 SSE Observer 完成。
 */
public record AiConversationGenerationBilledEvent(
        String generationPublicId,
        String eventName,
        String dataJson) {
}
