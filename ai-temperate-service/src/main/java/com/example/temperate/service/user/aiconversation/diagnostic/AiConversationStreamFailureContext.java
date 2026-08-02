package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.Objects;

/**
 * 承载发出 SSE error 前已经冻结的请求关联信息、输出计数和退款终态，不包含提示词或模型输出正文。
 */
public record AiConversationStreamFailureContext(
        String traceId,
        String usagePublicId,
        String conversationPublicId,
        String modelPublicId,
        String errorCode,
        boolean retryable,
        long emittedDeltaCount,
        int emittedTextChars,
        long elapsedMs,
        String billingState,
        String refundOutcome) {

    public AiConversationStreamFailureContext {
        traceId = Objects.requireNonNull(traceId);
        usagePublicId = Objects.requireNonNull(usagePublicId);
        conversationPublicId = Objects.requireNonNull(conversationPublicId);
        modelPublicId = Objects.requireNonNull(modelPublicId);
        errorCode = Objects.requireNonNull(errorCode);
        billingState = Objects.requireNonNull(billingState);
        refundOutcome = Objects.requireNonNull(refundOutcome);
    }
}
