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
        String durableCompactionJson,
        String ephemeralCompactionJson,
        List<AiConversationTurn> turns,
        int fieldCount) {

    public AiConversationContextSnapshot {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
