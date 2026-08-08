package com.example.temperate.service.user.aiconversation.context.usage;

/**
 * 定义按会话归属和目标模型读取权威上下文 Token 快照的业务边界。
 */
public interface AiConversationContextUsageService {

    AiConversationContextUsage getOwned(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId);

    AiConversationContextUsage get(
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId);

    AiConversationContextUsage get(
            byte[] conversationId,
            String conversationPublicId,
            long modelId);
}
