package com.example.temperate.service.user.aiconversation.model.stream;

/**
 * 定义活动阶段在一次流内允许公开的状态，供上游事件标准化和前端时间线共同使用。
 */
public enum AiConversationActivityStatus {
    STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    UNAVAILABLE
}
