package com.example.temperate.service.user.aiconversation.generation;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.TokenReservationMetering;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
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
        AiConversationVideoGenerationOptions videoGeneration,
        AiConversationWebSearchMode webSearchMode,
        byte[] idempotencyDigest,
        AiConversationReservationMetering metering,
        String traceId) {

    public AiConversationGenerationCreateCommand {
        conversationId = conversationId == null ? null : conversationId.clone();
        idempotencyDigest = idempotencyDigest == null ? null : idempotencyDigest.clone();
        modelPublicId = Objects.requireNonNull(modelPublicId);
        model = Objects.requireNonNull(model);
        input = Objects.requireNonNull(input);
        webSearchMode = Objects.requireNonNull(webSearchMode);
        metering = Objects.requireNonNull(metering);
        traceId = traceId == null ? "unavailable" : traceId;
    }

    public AiConversationGenerationCreateCommand(
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
        this(
                userId,
                conversationId,
                modelPublicId,
                model,
                reasoningEffort,
                input,
                imageGeneration,
                null,
                AiConversationWebSearchMode.OFF,
                idempotencyDigest,
                new TokenReservationMetering(
                        estimatedPromptTokens,
                        model.maxOutputTokens(),
                        model.inputRatio(),
                        model.cachedInputRatio(),
                        model.outputRatio()),
                traceId);
    }

    public AiConversationGenerationCreateCommand(
            long userId,
            byte[] conversationId,
            String modelPublicId,
            AiModelCacheEntry model,
            int reasoningEffort,
            AiConversationContent input,
            AiConversationImageGenerationOptions imageGeneration,
            byte[] idempotencyDigest,
            AiConversationReservationMetering metering,
            String traceId) {
        this(
                userId,
                conversationId,
                modelPublicId,
                model,
                reasoningEffort,
                input,
                imageGeneration,
                null,
                AiConversationWebSearchMode.OFF,
                idempotencyDigest,
                metering,
                traceId);
    }
}
