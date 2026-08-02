package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 将现有 Spring AI Chat Completions chunk 原样包装为项目标准模型事件，保证 OFF 模式行为不变。
 */
@Service
public final class ChatCompletionsStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiConversationModelClient modelClient;

    public ChatCompletionsStreamingStrategy(
            AiConversationModelClient modelClient) {
        this.modelClient = Objects.requireNonNull(modelClient);
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.CHAT_COMPLETIONS;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        return modelClient.stream(request.modelRequest())
                .map(AiConversationModelEvent.Chunk::new);
    }
}
