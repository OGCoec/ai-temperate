package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 定义只能由异步协调器调用的持久与用户停止草稿压缩执行边界。
 */
public interface AiConversationCompactionService {

    boolean compactDurable(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration,
            long cutoffMessageId);

    boolean compactEphemeral(
            String conversationPublicId,
            String cacheGeneration);
}
