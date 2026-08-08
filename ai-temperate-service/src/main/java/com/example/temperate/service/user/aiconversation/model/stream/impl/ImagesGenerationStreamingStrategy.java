package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingDiagnosticContext;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.runtime.AiConversationRuntimeFaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 通过 CLIProxyAPI Images Generation 端点生成图片，并把大体积图片 SSE 转成内部预览、最终图和 usage。
 *
 * <p>该策略不会自动重试模型请求，也不会记录请求正文或 Base64，避免重复扣费和敏感媒体泄露。</p>
 */
@Service
public final class ImagesGenerationStreamingStrategy
        implements AiConversationStreamingStrategy {

    private static final String CHECKPOINT_EVENT = "ai_image_stream_checkpoint";
    private static final Set<String> DIAGNOSTIC_UPSTREAM_PROTOCOL_TOKENS = Set.of(
            "message",
            "error",
            "image_generation.partial_image",
            "image_generation.completed",
            "image_edit.partial_image",
            "image_edit.completed",
            "response.image_generation_call.partial_image",
            "response.image_generation_call.completed",
            "response.completed",
            "response.failed");

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationImageGenerationProperties imageProperties;
    private final OpenAiImagesGenerationRequestFactory requestFactory;
    private final OpenAiImagesGenerationEventMapper eventMapper;
    private final AiConversationStreamFailureClassifier failureClassifier;
    private final AiConversationStreamTransportDiagnosticService
            transportDiagnosticService;
    private final AiConversationRuntimeFaultService runtimeFaultService;

    public ImagesGenerationStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this(
                webClientBuilder,
                inferenceProperties,
                imageProperties,
                objectMapper,
                failureClassifier,
                AiConversationStreamTransportDiagnosticService.noOp(),
                AiConversationRuntimeFaultService.withoutAvailabilitySignal());
    }

    @Override
    public AiModelProvider provider() {
        return AiModelProvider.OPENAI;
    }

    @Autowired
    public ImagesGenerationStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier,
            AiConversationStreamTransportDiagnosticService
                    transportDiagnosticService,
            AiConversationRuntimeFaultService runtimeFaultService) {
        this(
                webClientBuilder,
                inferenceProperties,
                imageProperties,
                objectMapper,
                failureClassifier,
                transportDiagnosticService,
                runtimeFaultService,
                new OpenAiImagesGenerationEventMapper(
                        objectMapper, imageProperties.maximumDecodedImageBytes()));
    }

    ImagesGenerationStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier,
            AiConversationStreamTransportDiagnosticService
                    transportDiagnosticService,
            AiConversationRuntimeFaultService runtimeFaultService,
            OpenAiImagesGenerationEventMapper eventMapper) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.requestFactory = new OpenAiImagesGenerationRequestFactory(objectMapper);
        this.eventMapper = Objects.requireNonNull(eventMapper);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.transportDiagnosticService = Objects.requireNonNull(
                transportDiagnosticService);
        this.runtimeFaultService = Objects.requireNonNull(runtimeFaultService);
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
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
        if (request.modelRequest().provider() != provider()) {
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "OpenAI 图片策略收到不匹配的模型供应商",
                    false));
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
            return Flux.error(new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "图片生成请求参数不完整",
                    false));
        }
        return Flux.defer(() -> {
            ImageStreamDiagnosticState diagnosticState =
                    new ImageStreamDiagnosticState();
            String requestPath = imageRequestPath(request);
            recordCheckpoint(
                    request,
                    "P0_CHILD_SUBSCRIBE",
                    details(
                            request,
                            "P0_CHILD_SUBSCRIBE",
                            "imageAction",
                            request.modelRequest().imageGeneration().action().name(),
                            "requestPath",
                            diagnosticRequestPath(requestPath)));
            return Flux.defer(() -> execute(
                            request, diagnosticState, requestPath))
                    .transform(upstream -> enforceTotalDeadline(
                            upstream,
                            inferenceProperties.maxStreamDuration()))
                    .onErrorMap(this::mapFailure)
                    .doFinally(signal -> recordSummary(
                            request, diagnosticState, signal));
        });
    }

    private Flux<AiConversationModelEvent> execute(
            AiConversationStreamingRequest request,
            ImageStreamDiagnosticState diagnosticState,
            String requestPath) {
        WebClient client = webClientBuilder.clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                .build();
        return client.post()
                .uri(requestPath)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestFactory.create(request))
                .exchangeToFlux(response -> decodeResponse(
                        response, request, diagnosticState))
                .flatMapSequential(
                        ImagesGenerationStreamingStrategy::rejectFailureEvent,
                        1,
                        1);
    }

    private Flux<AiConversationModelEvent> decodeResponse(
            ClientResponse response,
            AiConversationStreamingRequest request,
            ImageStreamDiagnosticState diagnosticState) {
        String responseContentType = responseContentType(response);
        recordCheckpoint(
                request,
                "P1_HTTP_RESPONSE",
                details(
                        request,
                        "P1_HTTP_RESPONSE",
                        "statusCode",
                        response.statusCode().value(),
                        "responseContentType",
                        responseContentType));
        if (!response.statusCode().is2xxSuccessful()) {
            return response.releaseBody().thenMany(Flux.error(
                    new ResponseStatusException(
                            response.statusCode(),
                            "AI upstream returned a non-success status")));
        }
        boolean eventStream = isEventStream(response);
        if (!eventStream) {
            return response.releaseBody().thenMany(Flux.error(
                    protocolFailure("AI upstream did not return image SSE")));
        }
        AiConversationImageGenerationOptions options =
                Objects.requireNonNull(request.modelRequest().imageGeneration());
        short outputIndex = request.modelRequest().outputIndex();
        return Flux.defer(() -> {
            int maximumEventCharacters = imageProperties.maximumSseCharacters();
            OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder(
                    maximumEventCharacters,
                    maximumEventCharacters);
            Flux<OpenAiResponsesSseEvent> decoded = response.bodyToFlux(
                            DataBuffer.class)
                    .concatMapIterable(buffer -> decodeBuffer(
                            decoder, buffer, diagnosticState));
            return decoded.concatWith(Flux.defer(() ->
                            Flux.fromIterable(decoder.finish())))
                    .concatMapIterable(event -> mapImageEvent(
                            event,
                            options,
                            outputIndex,
                            request,
                            diagnosticState));
        });
    }

    private List<AiConversationModelEvent> mapImageEvent(
            OpenAiResponsesSseEvent event,
            AiConversationImageGenerationOptions options,
            short outputIndex,
            AiConversationStreamingRequest request,
            ImageStreamDiagnosticState diagnosticState) {
        diagnosticState.parsedEvents.incrementAndGet();
        recordCheckpoint(
                request,
                "P2_SSE_EVENT_DECODED",
                details(
                        request,
                        "P2_SSE_EVENT_DECODED",
                        "upstreamEventName",
                        diagnosticUpstreamProtocolToken(
                                event == null ? null : event.name()),
                        "eventCharacters",
                        event == null || event.data() == null
                                ? 0 : event.data().length()));
        try {
            OpenAiImagesGenerationMappingResult mapped =
                    eventMapper.mapDetailed(event, options, outputIndex);
            diagnosticState.record(mapped.outcome());
            Map<String, Object> mappedDetails = details(
                    request,
                    "P3_EVENT_MAPPED",
                    "upstreamEventName",
                    diagnosticUpstreamProtocolToken(
                            mapped.upstreamEventName()),
                    "upstreamJsonType",
                    diagnosticUpstreamProtocolToken(
                            mapped.upstreamJsonType()),
                    "imagePayloadField",
                    mapped.imagePayloadField(),
                    "eventCharacters",
                    mapped.eventCharacters(),
                    "encodedImageCharacters",
                    mapped.encodedImageCharacters(),
                    "mappingOutcome",
                    mapped.outcome().name(),
                    "mappedEventCount",
                    mapped.events().size());
            if (mapped.partialImageIndex() != null) {
                mappedDetails.put(
                        "partialImageIndex", mapped.partialImageIndex());
            }
            if (mapped.outcome() == OpenAiImagesGenerationMappingOutcome.PARTIAL
                    || mapped.outcome()
                            == OpenAiImagesGenerationMappingOutcome.FINAL) {
                mappedDetails.put("mappedPhase", mapped.outcome().name());
            }
            recordCheckpoint(request, "P3_EVENT_MAPPED", mappedDetails);
            return mapped.events();
        } catch (LinkageError failure) {
            recordMappingFailure(request, diagnosticState, failure);
            // LinkageError 必须先转换为普通 Reactor 错误信号，否则 throwIfFatal 会绕过 onError/doFinally 并让 Worker 等到总超时。
            throw runtimeFaultService.imageEventMappingFailure(
                    generationPublicId(request), outputIndex, failure);
        } catch (AiConversationException controlled) {
            recordMappingFailure(request, diagnosticState, controlled);
            throw controlled;
        } catch (RuntimeException failure) {
            recordMappingFailure(request, diagnosticState, failure);
            // Base64、字节签名或事件结构异常均属于上游协议失败，禁止把未知内容继续送入预览和 OSS。
            throw protocolFailure(
                    "AI upstream returned an invalid image event",
                    failure);
        }
    }

    private static String generationPublicId(
            AiConversationStreamingRequest request) {
        AiConversationStreamingDiagnosticContext diagnosticContext =
                request.diagnosticContext();
        return diagnosticContext == null
                ? "unavailable"
                : diagnosticContext.generationPublicId();
    }

    private static List<OpenAiResponsesSseEvent> decodeBuffer(
            OpenAiResponsesSseDecoder decoder,
            DataBuffer buffer,
            ImageStreamDiagnosticState diagnosticState) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            diagnosticState.networkBytes.addAndGet(bytes.length);
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
                    "AI upstream image stream ended unsuccessfully"));
        }
        return Mono.just(event);
    }

    private static AiConversationException protocolFailure(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型图片响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException(message));
    }

    private static AiConversationException protocolFailure(
            String message,
            Throwable cause) {
        return new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型图片响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                new IllegalStateException(message, cause));
    }

    private static <T> Flux<T> enforceTotalDeadline(
            Flux<T> upstream,
            Duration maximumDuration) {
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
            case AI_UPSTREAM_TIMEOUT -> "模型图片响应超时";
            case AI_UPSTREAM_UNAVAILABLE -> "模型图片服务暂时不可用";
            default -> "模型图片响应未能完成";
        };
        return new AiConversationException(
                code,
                message,
                true,
                reason,
                failure);
    }

    private String imageRequestPath(AiConversationStreamingRequest request) {
        return request.modelRequest().imageGeneration().action()
                == AiConversationImageAction.EDIT
                ? imageProperties.editsPath()
                : imageProperties.generationsPath();
    }

    private static String responseContentType(ClientResponse response) {
        try {
            MediaType contentType = response.headers().contentType().orElse(null);
            if (contentType == null) {
                return "unavailable";
            }
            if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                return MediaType.TEXT_EVENT_STREAM_VALUE;
            }
            if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                return MediaType.APPLICATION_JSON_VALUE;
            }
            if (MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType)) {
                return MediaType.APPLICATION_PROBLEM_JSON_VALUE;
            }
            // Content-Type 参数和未知子类型均由上游控制，只记录受控分类，防止 URL 或凭证借 Header 进入日志。
            return "other";
        } catch (RuntimeException invalidHeader) {
            return "unavailable";
        }
    }

    private static boolean isEventStream(ClientResponse response) {
        try {
            return response.headers().contentType()
                    .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .isPresent();
        } catch (RuntimeException invalidHeader) {
            return false;
        }
    }

    private static String diagnosticUpstreamProtocolToken(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        return DIAGNOSTIC_UPSTREAM_PROTOCOL_TOKENS.contains(value)
                ? value
                : "unknown";
    }

    private static String diagnosticRequestPath(String value) {
        if ("/v1/images/generations".equals(value)
                || "/v1/images/edits".equals(value)) {
            return value;
        }
        return "custom";
    }

    private void recordMappingFailure(
            AiConversationStreamingRequest request,
            ImageStreamDiagnosticState diagnosticState,
            Throwable failure) {
        diagnosticState.mappingFailures.incrementAndGet();
        recordCheckpoint(
                request,
                "P3_EVENT_MAPPING_FAILED",
                details(
                        request,
                        "P3_EVENT_MAPPING_FAILED",
                        "failureStage",
                        "EVENT_MAP",
                        "failureType",
                        failure.getClass().getName()));
    }

    private void recordSummary(
            AiConversationStreamingRequest request,
            ImageStreamDiagnosticState diagnosticState,
            SignalType signal) {
        if (!diagnosticState.terminalRecorded.compareAndSet(false, true)) {
            return;
        }
        AiConversationStreamingDiagnosticContext context =
                request.diagnosticContext();
        if (context == null) {
            return;
        }
        Map<String, Object> summary = details(
                request,
                null,
                "signalType",
                diagnosticSignal(signal),
                "networkBytes",
                diagnosticState.networkBytes.get(),
                "parsedEvents",
                diagnosticState.parsedEvents.get(),
                "partialEvents",
                diagnosticState.partialEvents.get(),
                "finalEvents",
                diagnosticState.finalEvents.get(),
                "ignoredEvents",
                diagnosticState.ignoredEvents.get(),
                "mappingFailures",
                diagnosticState.mappingFailures.get());
        transportDiagnosticService.recordSafely(
                context.timingContext(),
                "ai_image_stream_summary",
                summary);
    }

    private static String diagnosticSignal(SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "COMPLETE";
            case ON_ERROR -> "ERROR";
            case CANCEL -> "CANCEL";
            default -> signal.name();
        };
    }

    private void recordCheckpoint(
            AiConversationStreamingRequest request,
            String checkpoint,
            Map<String, Object> details) {
        AiConversationStreamingDiagnosticContext context =
                request.diagnosticContext();
        if (context == null) {
            return;
        }
        details.put("checkpoint", checkpoint);
        transportDiagnosticService.recordSafely(
                context.timingContext(), CHECKPOINT_EVENT, details);
    }

    private static Map<String, Object> details(
            AiConversationStreamingRequest request,
            String checkpoint,
            Object... fields) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (request.diagnosticContext() != null) {
            details.put(
                    "generationPublicId",
                    request.diagnosticContext().generationPublicId());
        }
        details.put("outputIndex", request.modelRequest().outputIndex());
        if (checkpoint != null) {
            details.put("checkpoint", checkpoint);
        }
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Image diagnostic fields must use key/value pairs");
        }
        for (int index = 0; index < fields.length; index += 2) {
            details.put(String.valueOf(fields[index]), fields[index + 1]);
        }
        return details;
    }

    /**
     * 每个上游子流独立累计安全计数，终止时只输出一次汇总，且不持有任何图片正文。
     */
    private static final class ImageStreamDiagnosticState {
        private final AtomicLong networkBytes = new AtomicLong();
        private final AtomicLong parsedEvents = new AtomicLong();
        private final AtomicLong partialEvents = new AtomicLong();
        private final AtomicLong finalEvents = new AtomicLong();
        private final AtomicLong ignoredEvents = new AtomicLong();
        private final AtomicLong mappingFailures = new AtomicLong();
        private final AtomicBoolean terminalRecorded = new AtomicBoolean();

        private void record(OpenAiImagesGenerationMappingOutcome outcome) {
            switch (outcome) {
                case PARTIAL -> partialEvents.incrementAndGet();
                case FINAL -> finalEvents.incrementAndGet();
                case IGNORED -> ignoredEvents.incrementAndGet();
                case FAILURE, DONE, EMPTY -> {
                    // 这些结果有独立业务语义，不应被误计为未知协议事件。
                }
            }
        }
    }
}
