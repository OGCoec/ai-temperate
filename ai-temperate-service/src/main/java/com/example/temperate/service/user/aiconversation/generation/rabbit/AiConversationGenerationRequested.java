package com.example.temperate.service.user.aiconversation.generation.rabbit;

/**
 * 通知 Worker 领取已经持久化并完成预扣的 Generation，正文由 Worker 按公共 ID 回查 PostgreSQL。
 */
public record AiConversationGenerationRequested(
        String generationPublicId,
        String usagePublicId) {
}
