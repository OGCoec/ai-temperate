package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiinference.api.ApiInferenceClientRequestId;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.upstream.OpenAiUpstreamErrorDecoder;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticContext;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatUpstreamFailure;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatUpstreamFailureException;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamJson;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamStream;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.CodecException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该实现是来调用 8317 Chat JSON/SSE 端点，在响应提交前确定 Content-Type、安全响应头和可控 OpenAI 错误。
 */
@Service
public final class WebClientApiChatUpstreamClient implements ApiChatUpstreamClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final AiInferenceProperties properties;
    private final MeterRegistry meterRegistry;
    private final OpenAiUpstreamErrorDecoder errorDecoder;

    public WebClientApiChatUpstreamClient(
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
    public Mono<ApiChatUpstreamStream> stream(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request) {
        if (!properties.enabled()) {
            return Mono.error(unavailable("The model upstream is not enabled."));
        }
        return Mono.deferContextual(context -> {
            ApiChatDiagnosticContext.session(context).recordUpstreamAttempted();
            long startedNanos = System.nanoTime();
            AtomicBoolean firstChunk = new AtomicBoolean();
            return webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers -> forwardClientRequestId(headers, request))
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                            response -> errorDecoder.decode(response, request))
                    .toEntityFlux(SSE_TYPE)
                    .flatMap(entity -> {
                        MediaType contentType = entity.getHeaders().getContentType();
                        boolean sse = contentType != null
                                && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
                        ApiChatDiagnosticContext.session(context).recordUpstreamHeaders(
                                entity.getStatusCode().value(),
                                contentType == null ? "unknown" : contentType.toString(),
                                sse);
                        if (!sse) {
                            return Mono.error(protocolFailure(
                                    "The model upstream did not return an SSE response.",
                                    ApiChatProtocolViolation.NON_SSE_CONTENT_TYPE));
                        }
                        Flux<String> body = entity.getBody()
                                .map(ServerSentEvent::data)
                                .filter(Objects::nonNull)
                                .transform(source -> enforceMaximumDuration(
                                        source, properties.maxStreamDuration()))
                                .onErrorMap(CodecException.class, failure -> protocolFailure(
                                        "The model upstream returned an invalid SSE body.",
                                        ApiChatProtocolViolation.INVALID_SSE_BODY))
                                .doOnNext(ignored -> {
                                    if (firstChunk.compareAndSet(false, true)) {
                                        Timer.builder("api.chat.upstream.first.byte")
                                                .register(meterRegistry)
                                                .record(System.nanoTime() - startedNanos,
                                                        TimeUnit.NANOSECONDS);
                                    }
                                })
                                .doOnError(failure -> countOutcome("sse", failure))
                                .doFinally(signal -> recordDuration(
                                        "sse", signal.name(), startedNanos));
                        return Mono.just(new ApiChatUpstreamStream(
                                body,
                                ApiInferenceUpstreamHeaders.from(entity.getHeaders())));
                    })
                    .onErrorMap(WebClientRequestException.class, failure -> unavailable(
                            "The model upstream is unavailable.") );
        });
    }

    @Override
    public Mono<ApiChatUpstreamJson> create(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request) {
        if (!properties.enabled()) {
            return Mono.error(unavailable("The model upstream is not enabled."));
        }
        return Mono.deferContextual(context -> {
            ApiChatDiagnosticContext.session(context).recordUpstreamAttempted();
            long startedNanos = System.nanoTime();
            return webClient.post()
                    .uri("/v1/chat/completions")
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
                            return Mono.error(protocolFailure(
                                    "The model upstream did not return application/json.",
                                    ApiChatProtocolViolation.INVALID_FIELD_TYPE));
                        }
                        Timer.builder("api.chat.upstream.first.byte")
                                .register(meterRegistry)
                                .record(System.nanoTime() - startedNanos,
                                        TimeUnit.NANOSECONDS);
                        return Mono.just(new ApiChatUpstreamJson(
                                entity.getBody(),
                                ApiInferenceUpstreamHeaders.from(entity.getHeaders())));
                    })
                    .onErrorMap(WebClientRequestException.class, failure -> unavailable(
                            "The model upstream is unavailable."))
                    .onErrorMap(CodecException.class, failure -> protocolFailure(
                            "The model upstream returned invalid JSON.",
                            ApiChatProtocolViolation.INVALID_FIELD_TYPE))
                    .doOnError(failure -> countOutcome("json", failure))
                    .doFinally(signal -> recordDuration(
                            "json", signal.name(), startedNanos));
        });
    }

    private static void forwardClientRequestId(
            org.springframework.http.HttpHeaders headers,
            ApiInferenceUpstreamRequest request) {
        if (request != null && request.clientRequestId() != null) {
            headers.set(ApiInferenceClientRequestId.HEADER_NAME,
                    request.clientRequestId());
        }
    }

    private static Flux<String> enforceMaximumDuration(
            Flux<String> source,
            Duration maximumDuration) {
        return source.publish(shared -> {
            Flux<String> deadline = Mono.delay(maximumDuration)
                    .thenMany(Flux.<String>error(unavailable(
                            "The model upstream exceeded the maximum stream duration.")))
                    .takeUntilOther(shared.then(Mono.just(Boolean.TRUE)));
            return Flux.merge(shared, deadline);
        });
    }

    private void countOutcome(String mode, Throwable failure) {
        String result = failure instanceof ApiChatException controlled
                ? controlled.code().code() : "unexpected_error";
        meterRegistry.counter(
                "api.chat.upstream.outcome",
                "mode", mode,
                "result", result).increment();
    }

    private void recordDuration(String mode, String ending, long startedNanos) {
        Timer.builder("api.chat.upstream.duration")
                .tag("mode", mode)
                .tag("ending", ending.toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private static ApiChatException protocolFailure(
            String message,
            ApiChatProtocolViolation violation) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                message,
                null,
                new ApiChatProtocolViolationException(violation));
    }

    private static ApiChatException unavailable(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                message,
                null,
                new ApiChatUpstreamFailureException(
                        ApiChatUpstreamFailure.CONNECTION_FAILURE));
    }
}
