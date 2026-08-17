package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticContext;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatUpstreamFailure;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatUpstreamFailureException;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * 该实现是来以专用 WebClient 非阻塞调用 `127.0.0.1:8317/v1/chat/completions`，限制总流时长并把所有上游失败收敛为受控错误。
 */
@Service
public final class WebClientApiChatUpstreamClient implements ApiChatUpstreamClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final AiInferenceProperties properties;
    private final MeterRegistry meterRegistry;

    public WebClientApiChatUpstreamClient(
            @Qualifier("apiChatUpstreamWebClient") WebClient webClient,
            AiInferenceProperties properties,
            MeterRegistry meterRegistry) {
        this.webClient = Objects.requireNonNull(webClient);
        this.properties = Objects.requireNonNull(properties);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public Flux<String> stream(ObjectNode payload) {
        if (!properties.enabled()) {
            return Flux.error(new ApiChatException(
                    ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                    "The model upstream is not enabled.",
                    null));
        }
        return Flux.deferContextual(subscriptionContext -> {
            // 连接异常可能发生在收到任何响应头之前，因此必须在真正发起 8317 请求前记录边界。
            ApiChatDiagnosticContext.session(subscriptionContext)
                    .recordUpstreamAttempted();
            long startedNanos = System.nanoTime();
            AtomicBoolean firstChunk = new AtomicBoolean();
            return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .exchangeToFlux(response -> Flux.deferContextual(context -> {
                    MediaType contentType = response.headers().contentType().orElse(null);
                    boolean sse = contentType != null
                            && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
                    ApiChatDiagnosticContext.session(context).recordUpstreamHeaders(
                            response.statusCode().value(),
                            contentType == null ? "unknown" : contentType.toString(),
                            sse);
                    if (!response.statusCode().is2xxSuccessful()) {
                        // 必须释放但不读取或转发原始错误正文，防止供应商细节和内部配置泄露。
                        return response.releaseBody().thenMany(Flux.error(
                                protocolFailure(
                                        response.statusCode().is5xxServerError()
                                                ? ApiChatErrorCode.UPSTREAM_UNAVAILABLE
                                                : ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                                        "The model upstream rejected the request.",
                                        ApiChatProtocolViolation.UPSTREAM_REJECTED)));
                    }
                    if (!sse) {
                        return response.releaseBody().thenMany(Flux.error(
                                protocolFailure(
                                        ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                                        "The model upstream did not return an SSE response.",
                                        ApiChatProtocolViolation.NON_SSE_CONTENT_TYPE)));
                    }
                    return response.bodyToFlux(SSE_TYPE)
                            .map(ServerSentEvent::data)
                            .filter(Objects::nonNull);
                }))
                // `Flux.timeout(Duration)` 只限制相邻 chunk 空闲时间；这里使用独立总时钟保证整条流绝不超过十五分钟恢复边界。
                .transform(source -> enforceMaximumDuration(
                        source, properties.maxStreamDuration()))
                .onErrorMap(WebClientRequestException.class, exception ->
                        new ApiChatException(
                                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                                "The model upstream is unavailable.",
                                null,
                                new ApiChatUpstreamFailureException(
                                        ApiChatUpstreamFailure.CONNECTION_FAILURE)))
                .onErrorMap(CodecException.class, exception ->
                        protocolFailure(
                                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                                "The model upstream returned an invalid SSE body.",
                                ApiChatProtocolViolation.INVALID_SSE_BODY))
                .doOnNext(ignored -> {
                    if (firstChunk.compareAndSet(false, true)) {
                        Timer.builder("api.chat.upstream.first.byte")
                                .register(meterRegistry)
                                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
                    }
                })
                .doOnError(failure -> meterRegistry.counter(
                        "api.chat.upstream.outcome",
                        "result",
                        failure instanceof ApiChatException controlled
                                ? controlled.code().code() : "unexpected_error").increment())
                .doFinally(signal -> Timer.builder("api.chat.upstream.duration")
                        .tag("ending", signal.name().toLowerCase(java.util.Locale.ROOT))
                        .register(meterRegistry)
                        .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS));
        });
    }

    private static Flux<String> enforceMaximumDuration(
            Flux<String> source,
            java.time.Duration maximumDuration) {
        return source.publish(shared -> {
            Flux<String> deadline = Mono.delay(maximumDuration)
                    .thenMany(Flux.<String>error(new ApiChatException(
                            ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                            "The model upstream exceeded the maximum stream duration.",
                            null,
                            new ApiChatUpstreamFailureException(
                                    ApiChatUpstreamFailure.MAXIMUM_DURATION_EXCEEDED))))
                    .takeUntilOther(shared.then(Mono.just(Boolean.TRUE)));
            return Flux.merge(shared, deadline);
        });
    }

    private static ApiChatException protocolFailure(
            ApiChatErrorCode code,
            String message,
            ApiChatProtocolViolation violation) {
        return new ApiChatException(
                code,
                message,
                null,
                new ApiChatProtocolViolationException(violation));
    }
}
