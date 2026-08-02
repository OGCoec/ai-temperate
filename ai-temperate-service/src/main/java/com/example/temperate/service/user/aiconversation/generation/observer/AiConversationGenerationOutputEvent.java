package com.example.temperate.service.user.aiconversation.generation.observer;

/**
 * 表示 Redis Pub/Sub 广播的小型 Generation 输出事件，正文只用于实时展示且不会进入 RabbitMQ。
 */
public record AiConversationGenerationOutputEvent(
        int schemaVersion,
        String generationPublicId,
        long revision,
        String eventName,
        String dataJson) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
