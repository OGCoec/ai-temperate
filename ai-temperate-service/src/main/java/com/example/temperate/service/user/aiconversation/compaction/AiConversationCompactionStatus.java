package com.example.temperate.service.user.aiconversation.compaction;

/**
 * 定义对前端可见的会话压缩任务状态。
 */
public enum AiConversationCompactionStatus {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}
