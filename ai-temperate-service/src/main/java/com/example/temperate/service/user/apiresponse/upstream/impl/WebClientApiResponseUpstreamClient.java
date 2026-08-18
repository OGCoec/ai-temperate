package com.example.temperate.service.user.apiresponse.upstream.impl;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiinference.api.ApiInferenceClientRequestId;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseDecoder;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.example.temperate.service.user.aiinference.upstream.OpenAiUpstreamErrorDecoder;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamClient;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamJson;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该实现是来调用 8317 Responses JSON/SSE 端点并返回原始正文、安全头和可控错误，事件字段不在 HTTP 客户端层裁剪。
 */
@Service
public final class WebClientApiResponseUpstreamClient
        implements ApiResponseUpstreamClient {

    private final WebClient webClient;
    private final AiInferenceProperties properties;
    private final MeterRegistry meterRegistry;
    private final OpenAiUpstreamErrorDecoder errorDecoder;

    public WebClientApiResponseUpstreamClient(
            @Qualifier("apiChatUpstreamWebClient") WebClient webClient,
            AiInferenceProperties properties,
            MeterRegistry meterRegistry,
            OpenAiUpstreamErrorDecoder errorDecoder) {
        this.webClient = Objects.requireNonNull(webClient);
        this.properties = Objects.requireNonNull(properties);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.errorDecoder = Objects.requireNonNull(errorDecoder);
    }

    @Override
    public Mono<ApiResponseUpstreamStream> stream(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request) {
        if (!properties.enabled()) {
            return Mono.error(unavailable("The model upstream is not enabled."));
        }
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            AtomicBoolean firstByte = new AtomicBoolean();
            return webClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers -> forwardClientRequestId(headers, request))
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                            response -> errorDecoder.decode(response, request))
                    .toEntityFlux(DataBuffer.class)
                    .flatMap(entity -> {
                        MediaType contentType = entity.getHeaders().getContentType();
                        if (contentType == null
                                || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                            return Mono.error(protocol(
                                    "The model upstream did not return text/event-stream."));
                        }
                        Flux<ApiInferenceSseEvent> body = decodeSse(
                                entity.getBody(), firstByte, startedNanos)
                                .transform(source -> enforceMaximumDuration(
                                        source, properties.maxStreamDuration()))
                                .onErrorMap(CodecException.class, failure -> protocol(
                                        "The model upstream returned an invalid SSE body."))
                                .doOnError(failure -> countOutcome("sse", failure))
                                .doFinally(signal -> recordDuration(
                                        "sse", signal.name(), startedNanos));
                        return Mono.just(new ApiResponseUpstreamStream(
                                body,
                                ApiInferenceUpstreamHeaders.from(entity.getHeaders())));
                    })
                    .onErrorMap(WebClientRequestException.class, failure -> unavailable(
                            "The model upstream is unavailable."));
        });
    }

    @Override
    public Mono<ApiResponseUpstreamJson> create(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request) {
        if (!properties.enabled()) {
            return Mono.error(unavailable("The model upstream is not enabled."));
        }
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            return webClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> forwardClientRequestId(headers, request))
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                            response -> errorDecoder.decode(response, request))
                    .toEntity(JsonNode.class)
                    .timeout(properties.maxStreamDuration(),
                            Mono.error(unavailable(
                                    "The model upstream exceeded the maximum duration.")))
                    .flatMap(entity -> {
                        MediaType contentType = entity.getHeaders().getContentType();
                        if (contentType == null
                                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                                || entity.getBody() == null) {
                            return Mono.error(protocol(
                                    "The model upstream did not return application/json."));
                        }
                        Timer.builder("api.responses.upstream.first.byte")
                                .tag("mode", "json")
                                .register(meterRegistry)
                                .record(System.nanoTime() - startedNanos,
                                        TimeUnit.NANOSECONDS);
                        return Mono.just(new ApiResponseUpstreamJson(
                                entity.getBody(),
                                ApiInferenceUpstreamHeaders.from(entity.getHeaders())));
                    })
                    .onErrorMap(WebClientRequestException.class, failure -> unavailable(
                            "The model upstream is unavailable."))
                    .onErrorMap(CodecException.class, failure -> protocol(
                            "The model upstream returned invalid JSON."))
                    .doOnError(failure -> countOutcome("json", failure))
                    .doFinally(signal -> recordDuration(
                            "json", signal.name(), startedNanos));
        });
    }

    private Flux<ApiInferenceSseEvent> decodeSse(
            Flux<DataBuffer> buffers,
            AtomicBoolean firstByte,
            long startedNanos) {
        return Flux.defer(() -> {
            ApiInferenceSseDecoder decoder = new ApiInferenceSseDecoder();
            Flux<ApiInferenceSseEvent> decoded = buffers.concatMap(buffer ->
                    decodeBuffer(decoder, buffer, firstByte, startedNanos));
            return decoded.concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())));
        }).onErrorMap(IllegalStateException.class, failure -> protocol(
                "The model upstream returned an invalid SSE body."));
    }

    private Flux<ApiInferenceSseEvent> decodeBuffer(
            ApiInferenceSseDecoder decoder,
            DataBuffer buffer,
            AtomicBoolean firstByte,
            long startedNanos) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            if (bytes.length > 0 && firstByte.compareAndSet(false, true)) {
                Timer.builder("api.responses.upstream.first.byte")
                        .tag("mode", "sse")
                        .register(meterRegistry)
                        .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
            }
            return Flux.fromIterable(decoder.accept(bytes, 0, bytes.length));
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static void forwardClientRequestId(
            org.springframework.http.HttpHeaders headers,
            ApiInferenceUpstreamRequest request) {
        if (request != null && request.clientRequestId() != null) {
            headers.set(ApiInferenceClientRequestId.HEADER_NAME,
                    request.clientRequestId());
        }
    }

    private static <T> Flux<T> enforceMaximumDuration(
            Flux<T> source,
            Duration maximumDuration) {
        return source.publish(shared -> {
            Flux<T> deadline = Mono.delay(maximumDuration)
                    .thenMany(Flux.<T>error(unavailable(
                            "The model upstream exceeded the maximum stream duration.")))
                    .takeUntilOther(shared.then(Mono.just(Boolean.TRUE)));
            return Flux.merge(shared, deadline);
        });
    }

    private void countOutcome(String mode, Throwable failure) {
        String result = failure instanceof ApiChatException controlled
                ? controlled.code().code() : "unexpected_error";
        meterRegistry.counter(
                "api.responses.upstream.outcome",
                "mode", mode,
                "result", result).increment();
    }

    private void recordDuration(String mode, String ending, long startedNanos) {
        Timer.builder("api.responses.upstream.duration")
                .tag("mode", mode)
                .tag("ending", ending.toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private static ApiChatException protocol(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR, message, null);
    }

    private static ApiChatException unavailable(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE, message, null);
    }
}
