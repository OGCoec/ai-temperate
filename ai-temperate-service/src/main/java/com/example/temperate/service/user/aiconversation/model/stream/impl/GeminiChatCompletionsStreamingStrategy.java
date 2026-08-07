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
import com.example.temperate.service.user.aiconversation.model.stream.GeminiInteractionsStreamingClient;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 使用 Google Interactions 原生格式执行普通文本对话，禁止携带搜索工具和图片响应配置。
 */
@Service
public final class GeminiChatCompletionsStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiInferenceProperties inferenceProperties;
    private final AiConversationAttachmentService attachmentService;
    private final GeminiInteractionsStreamingClient client;
    private final GeminiInteractionsChatRequestFactory requestFactory;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public GeminiChatCompletionsStreamingStrategy(
            AiInferenceProperties inferenceProperties,
            AiConversationAttachmentService attachmentService,
            GeminiInteractionsStreamingClient client,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.client = Objects.requireNonNull(client);
        this.requestFactory = new GeminiInteractionsChatRequestFactory(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.GOOGLE;
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
            return Flux.error(invalid());
        }
        provider().validateReasoningEffort(request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled()) {
            return Flux.error(unavailable());
        }
        return Flux.defer(() -> client.stream(
                        requestFactory.create(
                                request, attachmentService::resolveModelUrl),
                        null,
                        request.modelRequest().outputIndex()))
                .onErrorMap(failure -> NativeProviderStreamingFailures.map(
                        failure, failureClassifier, "Google"));
    }

    private static AiConversationException invalid() {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Google 普通对话请求与策略不匹配", false);
    }

    private static AiConversationException unavailable() {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                "Google 模型服务当前未启用", true);
    }
}
