package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.config.AiConversationWebSearchProperties;
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
 * 使用 Anthropic 服务器端 Web Search 工具执行联网对话，并保持 TOKEN 计量和现有流事件契约。
 */
@Service
public final class AnthropicWebSearchStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiInferenceProperties inferenceProperties;
    private final AiConversationWebSearchProperties webSearchProperties;
    private final AiConversationAttachmentService attachmentService;
    private final AnthropicMessagesStreamingClient client;
    private final AnthropicWebSearchRequestFactory requestFactory;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public AnthropicWebSearchStreamingStrategy(
            AiInferenceProperties inferenceProperties,
            AiConversationWebSearchProperties webSearchProperties,
            AiConversationAttachmentService attachmentService,
            AnthropicMessagesStreamingClient client,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.webSearchProperties = Objects.requireNonNull(webSearchProperties);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.client = Objects.requireNonNull(client);
        this.requestFactory = new AnthropicWebSearchRequestFactory(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.ANTHROPIC;
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH;
    }

    @Override
    public AiConversationMeteringBasis meteringBasis() {
        return AiConversationMeteringBasis.TOKEN;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()
                || request.webSearchMode() == AiConversationWebSearchMode.OFF) {
            return Flux.error(invalid());
        }
        provider().validateReasoningEffort(request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled() || !webSearchProperties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "Anthropic 联网搜索当前未启用", true));
        }
        return Flux.defer(() -> client.stream(requestFactory.create(
                        request, attachmentService::resolveModelUrl)))
                .onErrorMap(failure -> NativeProviderStreamingFailures.map(
                        failure, failureClassifier, "Anthropic"));
    }

    private static AiConversationException invalid() {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Anthropic 联网搜索请求与策略不匹配", false);
    }
}
