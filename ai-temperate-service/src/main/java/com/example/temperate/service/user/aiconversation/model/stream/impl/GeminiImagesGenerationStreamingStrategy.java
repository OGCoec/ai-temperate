package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
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
 * 使用 Google Interactions 执行单槽位图片生成或编辑，并继续沿用项目 TOKEN 预扣与结算链路。
 */
@Service
public final class GeminiImagesGenerationStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final AiInferenceProperties inferenceProperties;
    private final AiConversationImageGenerationProperties imageProperties;
    private final GeminiInteractionsStreamingClient client;
    private final GeminiInteractionsImageRequestFactory requestFactory;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public GeminiImagesGenerationStreamingStrategy(
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            GeminiInteractionsStreamingClient client,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.client = Objects.requireNonNull(client);
        this.requestFactory = new GeminiInteractionsImageRequestFactory(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.GOOGLE;
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.IMAGES_GENERATION;
    }

    @Override
    public AiConversationMeteringBasis meteringBasis() {
        return AiConversationMeteringBasis.TOKEN;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()
                || request.webSearchMode() != AiConversationWebSearchMode.OFF
                || request.modelRequest().imageGeneration() == null) {
            return Flux.error(invalid());
        }
        provider().validateReasoningEffort(request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled() || !imageProperties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "Google 图片生成当前未启用", true));
        }
        return Flux.defer(() -> client.stream(
                        requestFactory.create(request),
                        request.modelRequest().imageGeneration(),
                        request.modelRequest().outputIndex()))
                .onErrorMap(failure -> NativeProviderStreamingFailures.map(
                        failure, failureClassifier, "Google"));
    }

    private static AiConversationException invalid() {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Google 图片请求与策略不匹配", false);
    }
}
