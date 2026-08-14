package com.example.temperate.service.user.apichat.impl;

import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ApiChatRequestValidator;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Reservation;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapterRegistry;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 该实现是来确保“并发准入→短事务预扣→连接 8317”的顺序，并在所有完成、错误与取消路径上幂等结算和释放租约。
 */
@Service
public final class ApiChatCompletionServiceImpl implements ApiChatCompletionService {

    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(15);

    private final ApiChatRequestValidator requestValidator;
    private final AiInferenceConcurrencyService concurrencyService;
    private final ApiChatBillingService billingService;
    private final ApiChatProviderAdapterRegistry adapterRegistry;
    private final ApiChatUpstreamClient upstreamClient;
    private final ApiChatSseParser sseParser;
    private final ObjectMapper objectMapper;
    private final Executor finalizerExecutor;
    private final MeterRegistry meterRegistry;

    public ApiChatCompletionServiceImpl(
            ApiChatRequestValidator requestValidator,
            AiInferenceConcurrencyService concurrencyService,
            ApiChatBillingService billingService,
            ApiChatProviderAdapterRegistry adapterRegistry,
            ApiChatUpstreamClient upstreamClient,
            ApiChatSseParser sseParser,
            ObjectMapper objectMapper,
            @Qualifier("aiConversationFinalizerExecutor") Executor finalizerExecutor,
            MeterRegistry meterRegistry) {
        this.requestValidator = Objects.requireNonNull(requestValidator);
        this.concurrencyService = Objects.requireNonNull(concurrencyService);
        this.billingService = Objects.requireNonNull(billingService);
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry);
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
        this.sseParser = Objects.requireNonNull(sseParser);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.finalizerExecutor = Objects.requireNonNull(finalizerExecutor);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public Flux<String> stream(ApiKeyPrincipal principal, ApiChatRequest request) {
        ValidatedApiChatRequest validated;
        ObjectNode payload;
        try {
            validated = requestValidator.validate(principal, request);
            // 适配失败必须发生在并发租约和额度预扣之前，避免纯请求校验错误占用资源或遗留 RESERVED 账单。
            payload = adapterRegistry
                    .getRequired(validated.model().vendor())
                    .adapt(validated);
        } catch (ApiChatException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw infrastructure("Request validation is temporarily unavailable.");
        }
        AiInferenceConcurrencyService.AcquireResult acquired =
                concurrencyService.tryAcquireApiKey(
                        principal.loginIdentityId(),
                        principal.digestIdentifier(),
                        (short) 1);
        if (acquired.result() != AiInferenceConcurrencyService.Result.ACQUIRED) {
            throw concurrencyException(acquired.result());
        }
        AiInferenceConcurrencyPermit permit = acquired.permit();
        Reservation reservation;
        try {
            reservation = billingService.reserve(principal, validated);
        } catch (ApiChatException exception) {
            concurrencyService.release(permit);
            throw exception;
        } catch (RuntimeException exception) {
            concurrencyService.release(permit);
            throw infrastructure("Billing is temporarily unavailable.");
        }

        return Flux.defer(() -> streamReserved(validated, reservation, permit, payload));
    }

    private Flux<String> streamReserved(
            ValidatedApiChatRequest validated,
            Reservation reservation,
            AiInferenceConcurrencyPermit permit,
            ObjectNode payload) {
        AtomicReference<Usage> usage = new AtomicReference<>();
        AtomicLong emittedBytes = new AtomicLong();
        AtomicReference<String> finishReason = new AtomicReference<>("STOP");
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean sseStarted = new AtomicBoolean();
        AtomicBoolean finalized = new AtomicBoolean();

        Flux<String> upstream = withLeaseRenewal(upstreamClient.stream(payload), permit)
                .concatMap(data -> {
                    ParsedChunk parsed = sseParser.parse(data);
                    if (done.get()) {
                        return Mono.error(protocolError());
                    }
                    if (usage.get() != null && !parsed.done()) {
                        // 最终 Usage 出现后只允许紧接 [DONE]；后续任意输出都会令已记录用量不再可信。
                        return Mono.error(protocolError());
                    }
                    if (parsed.output()) {
                        emittedBytes.addAndGet(parsed.outputUtf8Bytes());
                    }
                    if (parsed.finishReason() != null) {
                        finishReason.set(safeFinishReason(parsed.finishReason()));
                    }
                    if (parsed.done()) {
                        if (usage.get() == null) {
                            return Mono.error(protocolError());
                        }
                        done.set(true);
                        return Mono.fromRunnable(() -> billingService.settle(
                                reservation, usage.get(), finishReason.get()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .retry(2)
                                // 结算 I/O 失败与上游系统失败不是同一种终态；保留 RESERVED 让恢复任务处理，禁止给已完整输出的请求立即免单。
                                .onErrorMap(SettlementPendingException::new)
                                .thenReturn("[DONE]")
                                .doOnSuccess(ignored -> {
                                    sseStarted.set(true);
                                    finalized.set(true);
                                    count("settled");
                                });
                    }
                    if (parsed.usage() != null) {
                        if (!parsed.usageOnly()
                                || !usage.compareAndSet(null, parsed.usage())) {
                            return Mono.error(protocolError());
                        }
                        if (!validated.includeUsage()) {
                            return Mono.empty();
                        }
                    }
                    return Mono.fromSupplier(() -> {
                        sseStarted.set(true);
                        return parsed.serializedData();
                    });
                })
                .concatWith(Mono.defer(() -> done.get()
                        ? Mono.empty()
                        : Mono.error(protocolError())));

        return upstream
                .onErrorResume(exception -> {
                    boolean settlementPending =
                            hasCause(exception, SettlementPendingException.class);
                    ApiChatException controlled = settlementPending
                            ? infrastructure("Billing settlement is pending recovery.")
                            : controlled(exception);
                    if (settlementPending) {
                        count("settlement_pending_recovery");
                        if (!sseStarted.get()) {
                            return Flux.error(controlled);
                        }
                        return Flux.just(errorData(controlled), "[DONE]");
                    }
                    // 系统失败退款先原子占有终态；客户端同时断开时不得再以取消估算抢先收费。
                    boolean refundOwned = finalized.compareAndSet(false, true);
                    Mono<Void> refund = refundSystemFailure(
                            reservation, controlled, refundOwned);
                    if (!sseStarted.get()) {
                        // 首个 SSE 数据尚未写出时保留同步 HTTP 错误语义；异常处理器仍可返回 502/503 JSON。
                        return refund.thenMany(Flux.error(controlled));
                    }
                    return refund.thenMany(Flux.just(errorData(controlled), "[DONE]"));
                })
                .doFinally(signal -> {
                    concurrencyService.release(permit);
                    if (signal == reactor.core.publisher.SignalType.CANCEL
                            && finalized.compareAndSet(false, true)) {
                        // Servlet/Worker 断开不会等待数据库；有界终态执行器异步按 Usage、输出字节或零输出退款收敛。
                        try {
                            finalizerExecutor.execute(() -> runFinalizerWithRetry(() -> {
                                Usage finalUsage = usage.get();
                                if (finalUsage != null) {
                                    billingService.settle(
                                            reservation, finalUsage, "CLIENT_CANCELLED");
                                } else {
                                    billingService.settleCancellationEstimate(
                                            reservation, emittedBytes.get());
                                }
                                count("client_cancelled");
                            }));
                        } catch (RuntimeException schedulingFailure) {
                            // 执行器饱和时保留 RESERVED，恢复任务会在安全截止时间后退款，禁止在 I/O 回调线程执行数据库事务。
                            count("client_cancel_finalizer_rejected");
                        }
                    }
                });
    }

    private Mono<Void> refundSystemFailure(
            Reservation reservation,
            ApiChatException controlled,
            boolean refundOwned) {
        if (!refundOwned) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
                    billingService.refundSystemFailure(
                            reservation,
                            controlled.code().name());
                    count("system_failure_refunded");
                })
                .subscribeOn(Schedulers.boundedElastic())
                .retry(2)
                // 三次退款失败时保留 RESERVED 给恢复任务；客户端仍必须收到原始受控失败而非内部退款异常。
                .onErrorResume(refundFailure -> {
                    count("system_failure_refund_retry_exhausted");
                    return Mono.empty();
                })
                .then();
    }

    private Flux<String> withLeaseRenewal(
            Flux<String> source,
            AiInferenceConcurrencyPermit permit) {
        return source.publish(shared -> {
            // takeUntilOther 依赖 onNext 终止；then(Mono) 显式发出完成信号，避免上游正常结束后续租时钟继续存活。
            Mono<Boolean> completed = shared.then(Mono.just(Boolean.TRUE));
            Flux<String> renewalGuard = Flux.interval(RENEW_INTERVAL)
                    .takeUntilOther(completed)
                    .<String>handle((tick, sink) -> {
                        if (!concurrencyService.renew(permit)) {
                            sink.error(new ApiChatException(
                                    ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                                    "The concurrency lease could not be renewed.",
                                    null));
                        }
                    });
            return Flux.merge(shared, renewalGuard);
        });
    }

    private String errorData(ApiChatException exception) {
        ObjectNode envelope = objectMapper.createObjectNode();
        ObjectNode error = envelope.putObject("error");
        error.put("message", exception.getMessage());
        error.put("type", exception.code().type());
        if (exception.parameter() == null) {
            error.putNull("param");
        } else {
            error.put("param", exception.parameter());
        }
        error.put("code", exception.code().code());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException serializationFailure) {
            return "{\"error\":{\"message\":\"Streaming failed.\","
                    + "\"type\":\"server_error\",\"param\":null,"
                    + "\"code\":\"streaming_failed\"}}";
        }
    }

    private static ApiChatException concurrencyException(
            AiInferenceConcurrencyService.Result result) {
        ApiChatErrorCode code = switch (result) {
            case API_KEY_LIMIT_EXCEEDED -> ApiChatErrorCode.API_KEY_LIMIT_EXCEEDED;
            case ACCOUNT_LIMIT_EXCEEDED -> ApiChatErrorCode.ACCOUNT_LIMIT_EXCEEDED;
            case GLOBAL_LIMIT_EXCEEDED -> ApiChatErrorCode.GLOBAL_LIMIT_EXCEEDED;
            default -> ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE;
        };
        return new ApiChatException(code, switch (code) {
            case API_KEY_LIMIT_EXCEEDED -> "The API Key concurrency limit was exceeded.";
            case ACCOUNT_LIMIT_EXCEEDED -> "The account concurrency limit was exceeded.";
            case GLOBAL_LIMIT_EXCEEDED -> "The global concurrency limit was exceeded.";
            default -> "Concurrency control is temporarily unavailable.";
        }, null);
    }

    private static ApiChatException controlled(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiChatException apiChatException) {
                return apiChatException;
            }
            current = current.getCause();
        }
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                "The model stream failed.",
                null);
    }

    private static boolean hasCause(
            Throwable failure,
            Class<? extends Throwable> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ApiChatException protocolError() {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                "The model upstream did not provide a valid final Usage and [DONE].",
                null);
    }

    private static ApiChatException infrastructure(String message) {
        return new ApiChatException(
                ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                message,
                null);
    }

    private static String safeFinishReason(String value) {
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : "UNKNOWN";
    }

    private void runFinalizerWithRetry(Runnable finalizer) {
        RuntimeException lastFailure = null;
        // 三次有界尝试只重放同一幂等终态事务；全部失败时保留 RESERVED，由十七分钟恢复任务退款。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                finalizer.run();
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        count(lastFailure == null ? "finalizer_failed" : "finalizer_retry_exhausted");
    }

    private void count(String result) {
        meterRegistry.counter("api.chat.completion", "result", result).increment();
    }

    /** 该内部异常只标记终态结算尚未持久化，调用链必须保留 RESERVED 而不能执行系统失败退款。 */
    private static final class SettlementPendingException extends RuntimeException {
        private SettlementPendingException(Throwable cause) {
            super("API chat settlement remains pending", cause);
        }
    }
}
