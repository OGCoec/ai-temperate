package com.example.temperate.service.user.aiconversation.response;

/**
 * 描述单次 AI 会话请求从预扣到唯一结算终态的内存状态。
 */
public enum AiConversationRequestState {
    PREPARED,
    RESERVED,
    CONNECTING,
    STREAMING,
    FINALIZING_SUCCESS,
    FINALIZING_INTERRUPTED,
    SETTLED,
    FAILED_REFUNDED,
    RECONCILE_REQUIRED
}
