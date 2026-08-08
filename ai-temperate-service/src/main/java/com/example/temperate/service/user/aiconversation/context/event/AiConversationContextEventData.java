package com.example.temperate.service.user.aiconversation.context.event;

import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import java.time.OffsetDateTime;

/**
 * 表示上下文 SSE 每个事件共享的会话、revision、用量和压缩终态字段。
 */
public record AiConversationContextEventData(
        long eventRevision,
        long contextRevision,
        String conversationPublicId,
        String modelPublicId,
        String compactionOperationPublicId,
        String compactionStatus,
        String trigger,
        AiConversationContextUsage contextUsage,
        OffsetDateTime occurredAt,
        boolean retryable,
        String errorCode) {
}
