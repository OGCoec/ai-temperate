package com.example.temperate.service.user.aiconversation.context;

/**
 * 区分数据库持久轮次、仍在生成的临时轮次和允许进入后续上下文的中断轮次。
 */
public enum AiConversationTurnState {
    PERSISTED,
    STREAMING,
    INTERRUPTED
}
