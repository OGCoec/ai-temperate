package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;

/**
 * 承载开始一次模型调用前创建会话、幂等记录和最大额度预扣所需的可信参数。
 */
public record AiConversationReservationCommand(
        long userId,
        byte[] conversationId,
        AiModelCacheEntry model,
        byte[] idempotencyDigest,
        AiConversationReservationMetering metering) {

    public AiConversationReservationCommand {
        conversationId = conversationId == null ? null : conversationId.clone();
        idempotencyDigest = idempotencyDigest == null
                ? null
                : idempotencyDigest.clone();
        metering = java.util.Objects.requireNonNull(metering);
    }

    public AiConversationReservationCommand(
            long userId,
            byte[] conversationId,
            AiModelCacheEntry model,
            byte[] idempotencyDigest,
            long estimatedPromptTokens) {
        this(userId, conversationId, model, idempotencyDigest,
                new TokenReservationMetering(
                        estimatedPromptTokens,
                        model.maxOutputTokens(),
                        model.inputRatio(),
                        model.cachedInputRatio(),
                        model.outputRatio()));
    }
}
