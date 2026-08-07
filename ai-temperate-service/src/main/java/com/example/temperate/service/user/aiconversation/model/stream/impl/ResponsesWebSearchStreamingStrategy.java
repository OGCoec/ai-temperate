package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.config.AiConversationWebSearchProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 使用独立 WebClient 调用 CLIProxyAPI Responses 联网流，并把供应商 SSE 转换为项目内部事件。
 *
 * <p>该策略不自动重试、不记录请求或响应正文，也不改变 Spring MVC 下游；流式网络背压由 Reactor 链路直接传递。</p>
 */
@Service
public final class ResponsesWebSearchStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationWebSearchProperties webSearchProperties;
    private final AiConversationAttachmentService attachmentService;
    private final OpenAiResponsesRequestFactory requestFactory;
    private final OpenAiResponsesEventMapper eventMapper;
    private final AiUpstreamErrorCapture upstreamErrorCapture;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public ResponsesWebSearchStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationWebSearchProperties webSearchProperties,
            AiConversationAttachmentService attachmentService,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.webSearchProperties = Objects.requireNonNull(webSearchProperties);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.requestFactory = new OpenAiResponsesRequestFactory(objectMapper);
        this.eventMapper = new OpenAiResponsesEventMapper(objectMapper);
        this.upstreamErrorCapture = new AiUpstreamErrorCapture(objectMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.OPENAI;
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
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "OpenAI 联网策略收到不匹配的模型供应商",
                    false));
        }
        provider().validateReasoningEffort(
                request.modelRequest().reasoningEffort());
        if (!inferenceProperties.enabled() || !webSearchProperties.enabled()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "联网搜索功能当前未启用",
                    true));
        }
        if (request.webSearchMode() == AiConversationWebSearchMode.OFF) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "联网搜索策略不接受 OFF 模式",
                    false));
        }
        return Flux.defer(() -> execute(request))
                .transform(upstream -> enforceTotalDeadline(
                        upstream, inferenceProperties.maxStreamDuration()))
                .onErrorMap(this::mapFailure);
    }

    private Flux<AiConversationModelEvent> execute(
            AiConversationStreamingRequest request) {
        WebClient client = webClientBuilder.clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                .build();
        return client.post()
                .uri(webSearchProperties.responsesPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestFactory.create(
                        request, attachmentService::resolveModelUrl))
                .exchangeToFlux(this::decodeResponse)
                .flatMapSequential(
                        ResponsesWebSearchStreamingStrategy::rejectFailureEvent,
                        1,
                        1);
    }

    private Flux<AiConversationModelEvent> decodeResponse(
            ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            // 正文必须先在固定内存边界内完成脱敏；异常链只携带安全字段，原始内容不得进入 AOP 或用户响应。
            return upstreamErrorCapture.capture(response)
                    .flatMapMany(diagnostic -> Flux.error(
                            new AiUpstreamHttpStatusException(
                                    response.statusCode(),
                                    diagnostic)));
        }
        boolean eventStream = response.headers().contentType()
                .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                .isPresent();
        if (!eventStream) {
            return response.releaseBody().thenMany(Flux.error(
                    protocolFailure("AI upstream did not return SSE")));
        }
        return Flux.defer(() -> {
            OpenAiResponsesSseDecoder decoder =
                    new OpenAiResponsesSseDecoder();
            Flux<OpenAiResponsesSseEvent> decoded = response.bodyToFlux(
                            DataBuffer.class)
                    .concatMapIterable(buffer -> decodeBuffer(decoder, buffer));
            return decoded.concatWith(Flux.defer(() ->
                            Flux.fromIterable(decoder.finish())))
                    .concatMapIterable(eventMapper::map);
        });
    }

    private static List<OpenAiResponsesSseEvent> decodeBuffer(
            OpenAiResponsesSseDecoder decoder,
            DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return decoder.accept(bytes, 0, bytes.length);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static Mono<AiConversationModelEvent> rejectFailureEvent(
            AiConversationModelEvent event) {
        if (event instanceof AiConversationModelEvent.Failure) {
            return Mono.error(protocolFailure(
                    "AI upstream Responses stream ended unsuccessfully"));
        }
        return Mono.just(event);
    }

    private static AiConversationException protocolFailure(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型联网响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException(message));
    }

    private static <T> Flux<T> enforceTotalDeadline(
            Flux<T> upstream, Duration maximumDuration) {
        Mono<Void> deadline = Mono.delay(maximumDuration)
                .then(Mono.error(new TimeoutException(
                        "AI upstream maximum stream duration exceeded.")));
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
            case UPSTREAM_TOTAL_TIMEOUT ->
                    AiConversationErrorCode.AI_UPSTREAM_TIMEOUT;
            case UPSTREAM_RATE_LIMITED,
                    UPSTREAM_AUTH_UNAVAILABLE,
                    UPSTREAM_SERVER_ERROR ->
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE;
            default -> AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED;
        };
        String message = switch (code) {
            case AI_UPSTREAM_TIMEOUT -> "模型联网响应超时";
            case AI_UPSTREAM_UNAVAILABLE -> "模型联网服务暂时不可用";
            default -> "模型联网响应未能完成";
        };
        return new AiConversationException(
                code,
                message,
                true,
                reason,
                failure);
    }

}
