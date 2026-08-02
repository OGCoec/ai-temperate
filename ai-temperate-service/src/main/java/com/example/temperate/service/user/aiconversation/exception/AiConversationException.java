package com.example.temperate.service.user.aiconversation.exception;

/**
 * 表示能够映射为标准 HTTP 错误或 SSE error 事件的 AI 会话受控业务异常。
 */
public final class AiConversationException extends RuntimeException {

    private final AiConversationErrorCode code;
    private final boolean retryable;
    private final AiConversationStreamFailureReason reason;

    public AiConversationException(
            AiConversationErrorCode code, String message, boolean retryable) {
        this(code, message, retryable, null, null);
    }

    public AiConversationException(
            AiConversationErrorCode code,
            String message,
            boolean retryable,
            Throwable cause) {
        this(code, message, retryable, null, cause);
    }

    /**
     * 保留仅供服务端诊断的原始异常链，同时把可公开原因限制为固定枚举，避免把供应商响应泄露给客户端。
     */
    public AiConversationException(
            AiConversationErrorCode code,
            String message,
            boolean retryable,
            AiConversationStreamFailureReason reason,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.reason = reason;
    }

    public AiConversationErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public AiConversationStreamFailureReason reason() {
        return reason;
    }
}
