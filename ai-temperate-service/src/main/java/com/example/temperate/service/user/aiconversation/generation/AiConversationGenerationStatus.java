package com.example.temperate.service.user.aiconversation.generation;

/**
 * 定义异步生成任务从排队到计费终态的稳定数据库编码，观察者连接状态不属于本状态机。
 */
public enum AiConversationGenerationStatus {
    QUEUED(0),
    RUNNING(1),
    CANCEL_REQUESTED(2),
    TERMINAL_PENDING_BILLING(3),
    SETTLED(4),
    REFUNDED(5),
    RECONCILE_REQUIRED(6);

    private final int code;

    AiConversationGenerationStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean terminal() {
        return this == SETTLED || this == REFUNDED || this == RECONCILE_REQUIRED;
    }
}
