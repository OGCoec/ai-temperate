package com.example.temperate.service.user.aiconversation.compaction;

import java.time.OffsetDateTime;

/**
 * 表示 Redis 中单个会话当前可观察的压缩任务和事件版本。
 */
public record AiConversationCompactionOperation(
        String operationPublicId,
        long contextRevision,
        long eventRevision,
        AiConversationCompactionStatus status,
        AiConversationCompactionTrigger trigger,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String errorCode,
        boolean retryable) {

    public static AiConversationCompactionOperation idle(long eventRevision) {
        return new AiConversationCompactionOperation(
                null,
                0L,
                eventRevision,
                AiConversationCompactionStatus.IDLE,
                null,
                null,
                null,
                null,
                false);
    }
}
