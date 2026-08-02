package com.example.temperate.service.user.aiconversation.generation;

import java.time.OffsetDateTime;

/**
 * 向 Web 和 Observer 暴露不含内部数据库 ID、正文和余额的 Generation 状态快照。
 */
public record AiConversationGenerationView(
        String generationPublicId,
        String conversationPublicId,
        String usagePublicId,
        String status,
        String observerStatus,
        long observerEpoch,
        String cancelSource,
        String terminalType,
        String terminalReason,
        int terminalVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
