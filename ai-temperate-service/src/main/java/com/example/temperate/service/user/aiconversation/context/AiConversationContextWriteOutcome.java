package com.example.temperate.service.user.aiconversation.context;

/**
 * 表示 Redis 会话上下文写入是成功、generation 已过期还是缓存当前不可用。
 */
public enum AiConversationContextWriteOutcome {
    APPLIED,
    GENERATION_MISMATCH,
    UNAVAILABLE
}
