package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Objects;

/**
 * 承载一次模型订阅的安全公共标识和单调起始时间，不包含 Prompt、模型正文或认证凭据。
 */
public record AiConversationStreamTimingContext(
        String traceId,
        String usagePublicId,
        String conversationPublicId,
        String modelPublicId,
        AiConversationStreamTimingPath path,
        long startedNanos) {

    public AiConversationStreamTimingContext {
        traceId = Objects.requireNonNullElse(traceId, "unavailable");
        usagePublicId = Objects.requireNonNull(usagePublicId);
        conversationPublicId = Objects.requireNonNull(conversationPublicId);
        modelPublicId = Objects.requireNonNull(modelPublicId);
        path = Objects.requireNonNull(path);
    }
}
