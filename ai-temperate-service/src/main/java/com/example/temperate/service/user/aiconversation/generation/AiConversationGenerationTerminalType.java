package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义 Worker 冻结的事实终态类型；具体扣费或退款仍由既有终态计费策略决定。
 */
public enum AiConversationGenerationTerminalType {
    COMPLETED,
    CLIENT_CANCELLED,
    ADMIN_CANCELLED,
    UPSTREAM_FAILED,
    SYSTEM_FAILED
}
