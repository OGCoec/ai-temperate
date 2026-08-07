package com.example.temperate.service.user.aiconversation.context.event;

/**
 * 表示 Redis Pub/Sub 中只承载会话、事件类型和 revision 的小型唤醒通知。
 */
public record AiConversationContextEventNotification(
        int schemaVersion,
        String conversationPublicId,
        long eventRevision,
        String eventType) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
