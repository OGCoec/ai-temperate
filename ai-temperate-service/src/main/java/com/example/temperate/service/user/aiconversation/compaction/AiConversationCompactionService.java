package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 定义会话持久化历史的异步压缩和请求前受限同步压缩边界。
 */
public interface AiConversationCompactionService {

    void schedule(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration,
            long cutoffMessageId);

    void scheduleEphemeral(
            String conversationPublicId,
            String cacheGeneration);

    boolean compactSynchronously(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration);

    boolean compactEphemeralSynchronously(
            String conversationPublicId,
            String cacheGeneration);
}
