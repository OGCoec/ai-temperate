package com.example.temperate.service.user.aiconversation.compaction;

import java.util.Optional;

/**
 * 定义压缩任务状态、上下文事件 revision 和跨实例通知的 Redis 原子边界。
 */
public interface AiConversationCompactionStateStore {

    Optional<AiConversationCompactionOperation> find(String conversationPublicId);

    AiConversationCompactionClaim claim(
            String conversationPublicId,
            long contextRevision,
            AiConversationCompactionTrigger trigger);

    AiConversationCompactionOperation markRunning(
            String conversationPublicId,
            String operationPublicId);

    AiConversationCompactionOperation markCompleted(
            String conversationPublicId,
            String operationPublicId,
            long contextRevision);

    AiConversationCompactionOperation markFailed(
            String conversationPublicId,
            String operationPublicId,
            String errorCode,
            boolean retryable);

    long publishUsage(String conversationPublicId, long contextRevision);
}
