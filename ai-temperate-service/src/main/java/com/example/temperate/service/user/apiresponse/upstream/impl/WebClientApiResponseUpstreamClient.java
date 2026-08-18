package com.example.temperate.service.user.apiresponse.upstream.impl;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseDecoder;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamClient;
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
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该实现是来非阻塞调用 8317 Responses JSON/SSE 端点，校验成功 Content-Type，并把上游拒绝、协议错误和基础设施失败映射为受控状态。
 */
@Service
public final class WebClientApiResponseUpstreamClient
        implements ApiResponseUpstreamClient {

    private final WebClient webClient;
    private final AiInferenceProperties properties;
    private final MeterRegistry meterRegistry;

    public WebClientApiResponseUpstreamClient(
            @Qualifier("apiChatUpstreamWebClient") WebClient webClient,
            AiInferenceProperties properties,
            MeterRegistry meterRegistry) {
        this.webClient = Objects.requireNonNull(webClient);
        this.properties = Objects.requireNonNull(properties);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public Flux<ApiInferenceSseEvent> stream(ObjectNode payload) {
        if (!properties.enabled()) {
            return Flux.error(unavailable("The model upstream is not enabled."));
        }
        return Flux.defer(() -> {
            long startedNanos = System.nanoTime();
            AtomicBoolean firstByte = new AtomicBoolean();
            Flux<ApiInferenceSseEvent> source = webClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(payload)
                    .exchangeToFlux(response -> decodeSse(response, firstByte, startedNanos));
            return enforceMaximumDuration(source, properties.maxStreamDuration())
                    .onErrorMap(WebClientRequestException.class, failure ->
                            unavailable("The model upstream is unavailable."))
                    .onErrorMap(CodecException.class, failure ->
                            protocol("The model upstream returned an invalid SSE body."))
                    .doOnError(failure -> countOutcome("sse", failure))
                    .doFinally(signal -> recordDuration(
                            "sse", signal.name(), startedNanos));
        });
    }

    @Override
    public Mono<JsonNode> create(ObjectNode payload) {
        if (!properties.enabled()) {
            return Mono.error(unavailable("The model upstream is not enabled."));
        }
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            return webClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(this::decodeJson)
                    .timeout(properties.maxStreamDuration(),
                            Mono.error(unavailable(
                                    "The model upstream exceeded the maximum duration.")))
                    .onErrorMap(WebClientRequestException.class, failure ->
                            unavailable("The model upstream is unavailable."))
                    .onErrorMap(CodecException.class, failure ->
                            protocol("The model upstream returned invalid JSON."))
                    .doOnNext(ignored -> Timer.builder("api.responses.upstream.first.byte")
                            .tag("mode", "json")
                            .register(meterRegistry)
                            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS))
                    .doOnError(failure -> countOutcome("json", failure))
                    .doFinally(signal -> recordDuration(
                            "json", signal.name(), startedNanos));
        });
    }

    private Flux<ApiInferenceSseEvent> decodeSse(
            ClientResponse response,
            AtomicBoolean firstByte,
            long startedNanos) {
        if (!response.statusCode().is2xxSuccessful()) {
            ApiChatException failure = response.statusCode().is5xxServerError()
                    ? unavailable("The model upstream is unavailable.")
                    : protocol("The model upstream rejected the validated request.");
            // 原始错误正文可能包含供应商和内部路由信息，必须释放但不得读取或转发。
            return response.releaseBody().thenMany(Flux.error(failure));
        }
        MediaType contentType = response.headers().contentType().orElse(null);
        if (contentType == null
                || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            return response.releaseBody().thenMany(Flux.error(
                    protocol("The model upstream did not return text/event-stream.")));
        }
        return Flux.defer(() -> {
            ApiInferenceSseDecoder decoder = new ApiInferenceSseDecoder();
            Flux<ApiInferenceSseEvent> decoded = response.bodyToFlux(DataBuffer.class)
                    .concatMap(buffer -> decodeBuffer(
                            decoder, buffer, firstByte, startedNanos));
            return decoded.concatWith(Flux.defer(() ->
                    Flux.fromIterable(decoder.finish())));
        }).onErrorMap(IllegalStateException.class, failure ->
                protocol("The model upstream returned an invalid SSE body."));
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

    private Mono<JsonNode> decodeJson(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            ApiChatException failure = response.statusCode().is5xxServerError()
                    ? unavailable("The model upstream is unavailable.")
                    : protocol("The model upstream rejected the validated request.");
            return response.releaseBody().then(Mono.error(failure));
        }
        MediaType contentType = response.headers().contentType().orElse(null);
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return response.releaseBody().then(Mono.error(
                    protocol("The model upstream did not return application/json.")));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(protocol(
                        "The model upstream returned an empty JSON body.")));
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
