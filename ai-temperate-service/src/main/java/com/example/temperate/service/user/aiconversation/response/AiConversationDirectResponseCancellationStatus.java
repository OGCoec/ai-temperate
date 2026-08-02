package com.example.temperate.service.user.aiconversation.response;

/**
 * 定义直接 SSE 显式取消接口对客户端公开的有限状态，不暴露内部流、Redis 或计费细节。
 */
public enum AiConversationDirectResponseCancellationStatus {
    CANCEL_REQUESTED,
    ALREADY_TERMINAL,
    NOT_ACTIVE
}
