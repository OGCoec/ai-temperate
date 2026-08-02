package com.example.temperate.service.user.aiconversation.generation.rabbit;

import java.time.OffsetDateTime;

/**
 * 在固定宽限期后检查指定观察者代际是否仍然失联，旧代际到期只能成为空操作。
 */
public record AiConversationGenerationDetachCheck(
        String generationPublicId,
        long observerEpoch,
        OffsetDateTime detachedAt) {
}
