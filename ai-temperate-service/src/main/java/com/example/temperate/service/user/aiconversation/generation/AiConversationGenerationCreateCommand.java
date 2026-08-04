package com.example.temperate.service.user.aiconversation.generation;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import java.util.Objects;

/**
 * 承载网络与 Redis 准备工作完成后，单个 PostgreSQL 创建事务所需的可信 Generation 参数。
 */
public record AiConversationGenerationCreateCommand(
        long userId,
        byte[] conversationId,
        String modelPublicId,
        AiModelCacheEntry model,
        int reasoningEffort,
        AiConversationContent input,
        AiConversationImageGenerationOptions imageGeneration,
        byte[] idempotencyDigest,
        long estimatedPromptTokens,
        String traceId) {

    public AiConversationGenerationCreateCommand {
        conversationId = conversationId == null ? null : conversationId.clone();
        idempotencyDigest = idempotencyDigest == null ? null : idempotencyDigest.clone();
        modelPublicId = Objects.requireNonNull(modelPublicId);
        model = Objects.requireNonNull(model);
        input = Objects.requireNonNull(input);
        traceId = traceId == null ? "unavailable" : traceId;
    }
}
