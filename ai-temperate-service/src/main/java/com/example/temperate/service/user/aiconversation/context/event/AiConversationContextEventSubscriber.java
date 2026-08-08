package com.example.temperate.service.user.aiconversation.context.event;

import java.util.function.Consumer;

/**
 * 定义本实例按会话公共 ID 订阅上下文和压缩状态通知的边界。
 */
public interface AiConversationContextEventSubscriber {

    AutoCloseable subscribe(
            String conversationPublicId,
            Consumer<AiConversationContextEventNotification> listener);
}
