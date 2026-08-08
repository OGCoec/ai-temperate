package com.example.temperate.service.user.aiconversation.context;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 表示一次模型调用开始前冻结的会话上下文快照及其 Redis generation 元数据。
 */
public record AiConversationContextSnapshot(
        int schemaVersion,
        String generation,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        long lastCompactedMessageId,
        long latestPersistedMessageId,
        long estimatedContextTokens,
        long contextRevision,
        long durableCompactionTokens,
        long ephemeralCompactionTokens,
        OffsetDateTime updatedAt,
        OffsetDateTime lastCompactedAt,
        String durableCompactionJson,
        String ephemeralCompactionJson,
        List<AiConversationTurn> turns,
        int fieldCount) {

    public AiConversationContextSnapshot {
        turns = turns == null ? List.of() : List.copyOf(turns);
        if (estimatedContextTokens < 0L
                || contextRevision < 0L
                || durableCompactionTokens < 0L
                || ephemeralCompactionTokens < 0L) {
            throw new IllegalArgumentException(
                    "AI conversation context token metadata must not be negative.");
        }
    }
}
