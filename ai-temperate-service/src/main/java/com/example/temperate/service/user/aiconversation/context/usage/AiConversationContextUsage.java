package com.example.temperate.service.user.aiconversation.context.usage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 返回指定会话在指定模型窗口下的权威 Redis Token 快照和压缩状态。
 */
public record AiConversationContextUsage(
        String conversationPublicId,
        String modelPublicId,
        long estimatedContextTokens,
        long estimatedContextK,
        long contextWindowTokens,
        long contextWindowK,
        BigDecimal usagePercent,
        int thresholdPercent,
        boolean thresholdReached,
        boolean hardLimitExceeded,
        long contextRevision,
        String compactionStatus,
        String compactionOperationPublicId,
        OffsetDateTime updatedAt) {
}
