package com.example.temperate.service.user.aiconversation.response;

import reactor.core.publisher.Flux;

/**
 * 表示预检和预扣已经同步完成后的 accepted 事件及唯一一次订阅的后续流。
 */
public record AiConversationResponseStream(
        AiConversationStreamEvent accepted,
        Flux<AiConversationStreamEvent> events) {
}
