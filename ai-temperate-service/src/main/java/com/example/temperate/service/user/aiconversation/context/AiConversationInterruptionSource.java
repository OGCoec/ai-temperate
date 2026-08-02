package com.example.temperate.service.user.aiconversation.context;

/**
 * 区分 Redis 临时回答是由用户明确停止、传输断开还是系统失败产生，供后续上下文选择使用。
 */
public enum AiConversationInterruptionSource {
    USER_STOP,
    TRANSPORT_DISCONNECT,
    SYSTEM_FAILURE
}
