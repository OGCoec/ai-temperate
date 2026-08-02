package com.example.temperate.service.user.aiconversation.model.stream;

import reactor.core.publisher.Flux;

/**
 * 定义一种模型上游协议如何把请求转换为项目标准模型事件流。
 */
public interface AiConversationStreamingStrategy {

    AiConversationStreamingProtocol protocol();

    Flux<AiConversationModelEvent> stream(AiConversationStreamingRequest request);
}
