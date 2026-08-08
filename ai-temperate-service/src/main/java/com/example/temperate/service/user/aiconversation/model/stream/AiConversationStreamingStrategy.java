package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import reactor.core.publisher.Flux;

/**
 * 定义一种模型上游协议如何把请求转换为项目标准模型事件流。
 */
public interface AiConversationStreamingStrategy {

    AiModelProvider provider();

    AiConversationStreamingProtocol protocol();

    AiConversationMeteringBasis meteringBasis();

    Flux<AiConversationModelEvent> stream(AiConversationStreamingRequest request);
}
