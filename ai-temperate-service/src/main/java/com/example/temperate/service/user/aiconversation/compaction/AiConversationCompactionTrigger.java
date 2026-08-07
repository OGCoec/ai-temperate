package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 区分异步压缩由模型切换、回答终态、缓存安全阈值或硬容量等待触发。
 */
public enum AiConversationCompactionTrigger {
    MODEL_SWITCH,
    ANSWER_COMPLETED,
    USER_STOP,
    HASH_FIELD_SAFETY,
    HARD_LIMIT_WAIT
}
