package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通过 CLIProxyAPI 的 xAI Images 同步端点生成或编辑图片，并提取每个请求的精确成本 ticks。
 *
 * <p>该策略固定单请求单输出且不自动重试，避免生成重复图片和重复成本；非 2xx 正文只进入
 * 现有安全脱敏捕获，不记录 Prompt、Base64 或请求头。</p>
 */
@Service
public final class XaiImagesGenerationStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationImageGenerationProperties imageProperties;
    private final XaiImagesGenerationRequestFactory requestFactory;
    private final XaiImagesGenerationEventMapper eventMapper;
    private final AiUpstreamErrorCapture upstreamErrorCapture;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public XaiImagesGenerationStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.requestFactory = new XaiImagesGenerationRequestFactory(objectMapper);
        this.eventMapper = new XaiImagesGenerationEventMapper(
                imageProperties.maximumDecodedImageBytes());
        this.upstreamErrorCapture = new AiUpstreamErrorCapture(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.XAI;
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.IMAGES_GENERATION;
    }

    @Override
    public AiConversationMeteringBasis meteringBasis() {
        return AiConversationMeteringBasis.PROVIDER_COST_TICKS;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()) {
            return Flux.error(invalid("xAI 图片策略收到不匹配的模型供应商"));
        }
        provider().validateReasoningEffort(
                request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled() || !imageProperties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "图片生成功能当前未启用",
                    true));
        }
        if (request.webSearchMode() != AiConversationWebSearchMode.OFF
                || request.modelRequest().imageGeneration() == null) {
            return Flux.error(invalid("xAI 图片生成请求参数不完整"));
        }
        return Flux.defer(() -> execute(request))
                .transform(upstream -> enforceTotalDeadline(
                        upstream, inferenceProperties.maxStreamDuration()))
                .onErrorMap(this::mapFailure);
    }

    private Flux<AiConversationModelEvent> execute(
            AiConversationStreamingRequest request) {
        int maximumJsonBytes = maximumJsonBytes(
                imageProperties.maximumDecodedImageBytes());
        WebClient client = webClientBuilder.clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                // 同步 Base64 JSON 必须有明确内存上限，同时需要高于图片解码上限的 4/3 编码膨胀。
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(maximumJsonBytes))
                .build();
        return client.post()
                .uri(request.modelRequest().imageGeneration().action()
                                == AiConversationImageAction.EDIT
                        ? imageProperties.editsPath()
                        : imageProperties.generationsPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(requestFactory.create(request))
                .exchangeToFlux(response -> decodeResponse(response, request));
    }

    private static int maximumJsonBytes(int maximumDecodedImageBytes) {
        long encoded = Math.addExact(
                Math.multiplyExact((long) maximumDecodedImageBytes, 4L) / 3L,
                64L * 1024L);
        return Math.toIntExact(Math.min(encoded, Integer.MAX_VALUE));
    }

    private Flux<AiConversationModelEvent> decodeResponse(
            ClientResponse response,
            AiConversationStreamingRequest request) {
        if (!response.statusCode().is2xxSuccessful()) {
            return upstreamErrorCapture.capture(response)
                    .flatMapMany(diagnostic -> Flux.error(
                            new AiUpstreamHttpStatusException(
                                    response.statusCode(), diagnostic)));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(protocolFailure(
                        "xAI image response body is empty")))
                .flatMapMany(root -> Flux.fromIterable(eventMapper.map(
                        root,
                        request.modelRequest().imageGeneration(),
                        request.modelRequest().outputIndex())));
    }

    private static AiConversationException invalid(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message,
                false);
    }

    private static AiConversationException protocolFailure(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型图片响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException(message));
    }

    private static <T> Flux<T> enforceTotalDeadline(
            Flux<T> upstream,
            Duration maximumDuration) {
        Mono<Void> deadline = Mono.delay(maximumDuration)
                .then(Mono.error(new TimeoutException(
                        "xAI image maximum request duration exceeded.")));
        return upstream.takeUntilOther(deadline);
    }

    private Throwable mapFailure(Throwable failure) {
        if (failure instanceof AiConversationException) {
            return failure;
        }
        AiConversationStreamFailureClassification classification =
                failureClassifier.classify(failure);
        AiConversationStreamFailureReason reason = classification.reason();
        AiConversationErrorCode code = switch (reason) {
            case UPSTREAM_TOTAL_TIMEOUT -> AiConversationErrorCode.AI_UPSTREAM_TIMEOUT;
            case UPSTREAM_RATE_LIMITED,
                    UPSTREAM_AUTH_UNAVAILABLE,
                    UPSTREAM_SERVER_ERROR -> AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE;
            default -> AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED;
        };
        return new AiConversationException(
                code,
                code == AiConversationErrorCode.AI_UPSTREAM_TIMEOUT
                        ? "模型图片响应超时"
                        : code == AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE
                                ? "模型图片服务暂时不可用"
                                : "模型图片响应未能完成",
                true,
                reason,
                failure);
    }
}
