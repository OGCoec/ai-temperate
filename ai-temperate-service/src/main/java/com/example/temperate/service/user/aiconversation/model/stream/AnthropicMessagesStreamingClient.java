package com.example.temperate.service.user.aiconversation.model.stream;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

/**
 * 定义 Anthropic Messages 原生流式传输边界，使策略只负责协议选择和请求构造，不直接处理网络缓冲区。
 */
public interface AnthropicMessagesStreamingClient {

    Flux<AiConversationModelEvent> stream(JsonNode requestBody);
}
