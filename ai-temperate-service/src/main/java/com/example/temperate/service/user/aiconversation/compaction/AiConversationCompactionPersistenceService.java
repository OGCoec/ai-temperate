package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 定义压缩模型调用完成后的 PostgreSQL 检查点 CAS 短事务，避免网络调用进入数据库事务。
 */
public interface AiConversationCompactionPersistenceService {

    boolean compareAndSet(
            byte[] conversationId,
            Long expectedCheckpoint,
            long cutoffMessageId,
            String compactedContextJson);
}
