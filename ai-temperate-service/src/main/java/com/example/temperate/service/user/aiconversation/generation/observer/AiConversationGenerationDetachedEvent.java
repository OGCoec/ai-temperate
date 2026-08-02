package com.example.temperate.service.user.aiconversation.generation.observer;

import java.time.OffsetDateTime;

/**
 * 表示指定 observerEpoch 已在 PostgreSQL 提交为 DETACHED，可发布三十秒延迟检查。
 */
public record AiConversationGenerationDetachedEvent(
        String generationPublicId,
        long observerEpoch,
        OffsetDateTime detachedAt,
        String traceId) {
}
