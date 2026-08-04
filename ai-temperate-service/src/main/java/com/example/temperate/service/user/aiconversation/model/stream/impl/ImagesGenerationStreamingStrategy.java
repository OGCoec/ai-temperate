package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通过 CLIProxyAPI Images Generation 端点生成图片，并把大体积图片 SSE 转成内部预览、最终图和 usage。
 *
 * <p>该策略不会自动重试模型请求，也不会记录请求正文或 Base64，避免重复扣费和敏感媒体泄露。</p>
 */
@Service
public final class ImagesGenerationStreamingStrategy
        implements AiConversationStreamingStrategy {

    private final WebClient.Builder webClientBuilder;
    private final AiInferenceProperties inferenceProperties;
    private final AiConversationImageGenerationProperties imageProperties;
    private final OpenAiImagesGenerationRequestFactory requestFactory;
    private final OpenAiImagesGenerationEventMapper eventMapper;
    private final AiConversationStreamFailureClassifier failureClassifier;

    public ImagesGenerationStreamingStrategy(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationImageGenerationProperties imageProperties,
            ObjectMapper objectMapper,
            AiConversationStreamFailureClassifier failureClassifier) {
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.requestFactory = new OpenAiImagesGenerationRequestFactory(objectMapper);
        this.eventMapper = new OpenAiImagesGenerationEventMapper(
                objectMapper, imageProperties.maximumDecodedImageBytes());
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    public AiConversationStreamingProtocol protocol() {
        return AiConversationStreamingProtocol.IMAGES_GENERATION;
    }

    @Override
    public Flux<AiConversationModelEvent> stream(
            AiConversationStreamingRequest request) {
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
                .uri(imageProperties.generationsPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestFactory.create(request))
                .exchangeToFlux(response -> decodeResponse(response, request))
                .flatMapSequential(
                        ImagesGenerationStreamingStrategy::rejectFailureEvent,
                        1,
                        1);
    }

    private Flux<AiConversationModelEvent> decodeResponse(
            ClientResponse response,
            AiConversationStreamingRequest request) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.releaseBody().thenMany(Flux.error(
                    new ResponseStatusException(
                            response.statusCode(),
                            "AI upstream returned a non-success status")));
        }
        boolean eventStream = response.headers().contentType()
                .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                .isPresent();
        if (!eventStream) {
            return response.releaseBody().thenMany(Flux.error(
                    protocolFailure("AI upstream did not return image SSE")));
        }
        AiConversationImageGenerationOptions options =
                Objects.requireNonNull(request.modelRequest().imageGeneration());
        return Flux.defer(() -> {
            int maximumEventCharacters = imageProperties.maximumSseCharacters();
            OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder(
                    maximumEventCharacters,
                    maximumEventCharacters);
            Flux<OpenAiResponsesSseEvent> decoded = response.bodyToFlux(
                            DataBuffer.class)
                    .concatMapIterable(buffer -> decodeBuffer(decoder, buffer));
            return decoded.concatWith(Flux.defer(() ->
                            Flux.fromIterable(decoder.finish())))
                    .concatMapIterable(event -> mapImageEvent(event, options));
        });
    }

    private List<AiConversationModelEvent> mapImageEvent(
            OpenAiResponsesSseEvent event,
            AiConversationImageGenerationOptions options) {
        try {
            return eventMapper.map(event, options);
        } catch (AiConversationException controlled) {
            throw controlled;
        } catch (RuntimeException failure) {
            // Base64、字节签名或事件结构异常均属于上游协议失败，禁止把未知内容继续送入预览和 OSS。
            throw protocolFailure(
                    "AI upstream returned an invalid image event",
                    failure);
        }
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
}
