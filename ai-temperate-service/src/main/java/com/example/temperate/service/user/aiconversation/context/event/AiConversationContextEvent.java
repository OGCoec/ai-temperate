package com.example.temperate.service.user.aiconversation.context.event;

/**
 * 表示一个具名上下文 SSE 事件及其结构化数据。
 */
public record AiConversationContextEvent(
        String name,
        AiConversationContextEventData data) {
}
