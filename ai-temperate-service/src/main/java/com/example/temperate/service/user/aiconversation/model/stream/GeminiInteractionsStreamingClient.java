package com.example.temperate.service.user.aiconversation.model.stream;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

/**
 * 定义 Google Interactions 原生流式传输边界，并用显式图片上下文区分文本事件和严格单图聚合。
 */
public interface GeminiInteractionsStreamingClient {

    Flux<AiConversationModelEvent> stream(
            JsonNode requestBody,
            AiConversationImageGenerationOptions imageOptions,
            short outputIndex);
}
