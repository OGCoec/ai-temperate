package com.example.temperate.service.user.aiconversation.generation.observer;

import java.util.function.Consumer;

/**
 * 定义本实例按 Generation 公共 ID 订阅 Redis 易失输出通知的边界。
 */
public interface AiConversationGenerationOutputSubscriber {

    AutoCloseable subscribe(
            String generationPublicId,
            Consumer<AiConversationGenerationOutputEvent> listener);
}
