package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示 SSE 建立后能够安全公开的受控错误，不包含内部 ID、上游响应体或异常堆栈。
 */
public record AiConversationErrorData(
        String code,
        String reasonCode,
        boolean retryable,
        String usagePublicId,
        String message,
        long sequence) {

    public AiConversationErrorData(
            String code,
            String reasonCode,
            boolean retryable,
            String usagePublicId,
            String message) {
        this(code, reasonCode, retryable, usagePublicId, message, 0L);
    }
}
