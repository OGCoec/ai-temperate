package com.example.temperate.service.user.aiconversation.context;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;

/**
 * 定义从 Redis 或 PostgreSQL 重建会话、组装固定顺序 Prompt 并执行上下文预算检查的业务边界。
 */
public interface AiConversationContextService {

    AiConversationContextSnapshot load(
            byte[] conversationId,
            String conversationPublicId);

    AiConversationPromptSnapshot prepareNew(
            AiModelCacheEntry model,
            AiConversationContent currentInput);

    AiConversationPromptSnapshot prepare(
            byte[] conversationId,
            String conversationPublicId,
            AiModelCacheEntry model,
            AiConversationContent currentInput);
}
