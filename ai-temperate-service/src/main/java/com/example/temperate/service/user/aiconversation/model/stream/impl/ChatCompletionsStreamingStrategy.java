package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
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
    public AiModelProvider provider() {
        return AiModelProvider.OPENAI;
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.CHAT_COMPLETIONS;
    }

    @Override
    public AiConversationMeteringBasis meteringBasis() {
        return AiConversationMeteringBasis.TOKEN;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "OpenAI 对话策略收到不匹配的模型供应商",
                    false));
        }
        return Flux.defer(() -> {
            provider().validateReasoningEffort(
                    request.modelRequest().reasoningEffort());
            return modelClient.stream(request.modelRequest())
                    .map(AiConversationModelEvent.Chunk::new);
        });
    }
}
