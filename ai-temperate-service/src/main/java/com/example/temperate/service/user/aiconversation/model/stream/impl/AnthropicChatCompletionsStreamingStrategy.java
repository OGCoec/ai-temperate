package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.model.stream.AnthropicMessagesStreamingClient;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 使用 Anthropic Messages 原生格式执行普通对话，并在发起上游请求前完成供应商和推理档位校验。
 */
@Service
public final class AnthropicChatCompletionsStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiInferenceProperties inferenceProperties;
    private final AiConversationAttachmentService attachmentService;
    private final AnthropicMessagesStreamingClient client;
    private final AnthropicMessagesRequestFactory requestFactory;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public AnthropicChatCompletionsStreamingStrategy(
            AiInferenceProperties inferenceProperties,
            AiConversationAttachmentService attachmentService,
            AnthropicMessagesStreamingClient client,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.client = Objects.requireNonNull(client);
        this.requestFactory = new AnthropicMessagesRequestFactory(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.ANTHROPIC;
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
    public Flux<AiConversationModelEvent> stream(AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()
                || request.webSearchMode() != AiConversationWebSearchMode.OFF) {
            return Flux.error(invalid("Anthropic 普通对话请求与策略不匹配"));
        }
        provider().validateReasoningEffort(request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled()) {
            return Flux.error(unavailable());
        }
        return Flux.defer(() -> client.stream(requestFactory.create(
                        request, attachmentService::resolveModelUrl)))
                .onErrorMap(failure -> NativeProviderStreamingFailures.map(
                        failure, failureClassifier, "Anthropic"));
    }

    private static AiConversationException invalid(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID, message, false);
    }

    private static AiConversationException unavailable() {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                "Anthropic 模型服务当前未启用", true);
    }
}
