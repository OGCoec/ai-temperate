package com.example.temperate.service.user.apichat.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleSession;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceSettlementPendingException;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ApiChatRequestValidator;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticBoundary;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticContext;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticSession;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticStage;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatFrameKind;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnostic;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnosticService;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapterRegistry;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.Normalization;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedEvent;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该实现是来确保“并发准入→短事务预扣→连接 8317”的顺序，并在所有完成、错误与取消路径上幂等结算和释放租约。
 */
@Service
public final class ApiChatCompletionServiceImpl implements ApiChatCompletionService {

    private final ApiChatRequestValidator requestValidator;
    private final ApiInferenceLifecycleService lifecycleService;
    private final ApiChatProviderAdapterRegistry adapterRegistry;
    private final ApiChatUpstreamClient upstreamClient;
    private final ApiChatSseParser sseParser;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ApiChatStreamDiagnosticService diagnostics;

    public ApiChatCompletionServiceImpl(
            ApiChatRequestValidator requestValidator,
            ApiInferenceLifecycleService lifecycleService,
            ApiChatProviderAdapterRegistry adapterRegistry,
            ApiChatUpstreamClient upstreamClient,
            ApiChatSseParser sseParser,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ApiChatStreamDiagnosticService diagnostics) {
        this.requestValidator = Objects.requireNonNull(requestValidator);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry);
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
        this.sseParser = Objects.requireNonNull(sseParser);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.COMPLETION_SERVICE)
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
        ApiInferenceLifecycleSession session = lifecycleService.start(
                principal,
                new ApiInferenceExecutionRequest(
                        validated.model(),
                        validated.effectiveMaxOutputTokens(),
                        validated.estimatedPromptTokens(),
                        true,
                        ApiInferenceProtocol.CHAT_COMPLETIONS));

        return Flux.defer(() -> streamReserved(validated, session, payload));
    }

    private Flux<String> streamReserved(
            ValidatedApiChatRequest validated,
            ApiInferenceLifecycleSession session,
            ObjectNode payload) {
        AtomicReference<ApiInferenceUsage> usage = new AtomicReference<>();
        AtomicLong emittedBytes = new AtomicLong();
        AtomicReference<String> finishReason = new AtomicReference<>("STOP");
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean sseStarted = new AtomicBoolean();

        Flux<String> rawUpstream = diagnostics.observeBoundary(
                lifecycleService.withLeaseRenewal(upstreamClient.stream(payload), session),
                ApiChatDiagnosticBoundary.UPSTREAM_RAW,
                data -> data.getBytes(StandardCharsets.UTF_8).length,
                data -> "[DONE]".equals(data)
                        ? ApiChatFrameKind.DONE : ApiChatFrameKind.DATA);
        Flux<String> upstream = rawUpstream
                .concatMap(data -> Flux.deferContextual(context -> {
                    ApiChatDiagnosticSession diagnosticSession =
                            ApiChatDiagnosticContext.session(context);
                    ParsedEvent parsedEvent = sseParser.parse(data);
                    if (parsedEvent.normalization()
                            == Normalization.COMBINED_CHOICES_AND_USAGE) {
                        diagnosticSession.recordNormalization(
                                parsedEvent.normalization(),
                                parsedEvent.chunks().size());
                        count("combined_usage_normalized");
                    }
                    // concatMap 把同一上游事件拆出的 choices 与 Usage 串行送入状态机，禁止重排后先看到 Usage。
                    return Flux.fromIterable(parsedEvent.chunks())
                            .concatMap(parsed -> processParsedChunk(
                                    parsed,
                                    validated,
                                    session,
                                    diagnosticSession,
                                    usage,
                                    emittedBytes,
                                    finishReason,
                                    done,
                                    sseStarted));
                }))
                .concatWith(Mono.defer(() -> done.get()
                        ? Mono.empty()
                        : Mono.error(protocolError(
                                ApiChatProtocolViolation.STREAM_ENDED_WITHOUT_DONE))));

        Flux<String> guarded = upstream
                .onErrorResume(exception -> Flux.deferContextual(context -> {
                    var diagnosticSession = ApiChatDiagnosticContext.session(context);
                    diagnosticSession.recordFailure(exception);
                    boolean settlementPending =
                            hasCause(exception, ApiInferenceSettlementPendingException.class);
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
                    // 生命周期服务以请求级 CAS 决定退款、取消或结算的唯一终态，避免并发回调重复扣费。
                    Mono<Void> refund = lifecycleService.refundSystemFailure(
                            session, controlled.code().name());
                    if (!sseStarted.get()) {
                        // 首个 SSE 数据尚未写出时保留同步 HTTP 错误语义；异常处理器仍可返回 502/503 JSON。
                        return refund.thenMany(Flux.error(controlled));
                    }
                    return refund.thenMany(Flux.just(errorData(controlled), "[DONE]"));
                }));

        return diagnostics.observeBoundary(
                        guarded,
                        ApiChatDiagnosticBoundary.SSE_EVENT_READY,
                        data -> data.getBytes(StandardCharsets.UTF_8).length,
                        ApiChatCompletionServiceImpl::outgoingFrameKind)
                .doFinally(signal -> {
                    lifecycleService.release(session);
                    if (signal == reactor.core.publisher.SignalType.CANCEL) {
                        // 取消终态由有界执行器异步收敛；若已有权威终态，生命周期 CAS 会让此调用成为空操作。
                        lifecycleService.scheduleCancellation(
                                session, usage.get(), emittedBytes.get());
                    }
                });
    }

    private Mono<String> processParsedChunk(
            ParsedChunk parsed,
            ValidatedApiChatRequest validated,
            ApiInferenceLifecycleSession session,
            ApiChatDiagnosticSession diagnosticSession,
            AtomicReference<ApiInferenceUsage> usage,
            AtomicLong emittedBytes,
            AtomicReference<String> finishReason,
            AtomicBoolean done,
            AtomicBoolean sseStarted) {
        ApiChatFrameKind frameKind = frameKind(parsed);
        diagnosticSession.recordBoundary(
                ApiChatDiagnosticBoundary.AFTER_PROTOCOL_PARSE,
                parsed.serializedData().getBytes(StandardCharsets.UTF_8).length,
                frameKind);
        if (done.get()) {
            return Mono.error(protocolError(ApiChatProtocolViolation.DATA_AFTER_DONE));
        }
        if (parsed.done()) {
            ApiInferenceUsage finalUsage = usage.get();
            if (finalUsage == null) {
                return Mono.error(protocolError(
                        ApiChatProtocolViolation.DONE_WITHOUT_USAGE));
            }
            done.set(true);
            diagnosticSession.recordDone();
            return lifecycleService.settle(session, finalUsage, finishReason.get())
                    .thenReturn("[DONE]")
                    .doOnSuccess(ignored -> {
                        sseStarted.set(true);
                        diagnosticSession.recordBoundary(
                                ApiChatDiagnosticBoundary.AFTER_BUSINESS_GATE,
                                "[DONE]".length(),
                                ApiChatFrameKind.DONE);
                        count("settled");
                    });
        }
        if (parsed.usage() != null) {
            // CAS 是唯一重复 Usage 判据；合并终态已在解析边界拆分，不能再靠帧形状推断重复。
            if (!usage.compareAndSet(null, parsed.usage())) {
                return Mono.error(protocolError(
                        ApiChatProtocolViolation.DUPLICATE_USAGE));
            }
            diagnosticSession.recordUsage(parsed.usage(), validated.includeUsage());
            if (!validated.includeUsage()) {
                return Mono.empty();
            }
        } else if (usage.get() != null) {
            // 第一份最终 Usage 后只允许真实上游 [DONE]；普通数据会令计费终态不再可信。
            return Mono.error(protocolError(
                    ApiChatProtocolViolation.DATA_AFTER_USAGE));
        }
        if (parsed.output()) {
            emittedBytes.addAndGet(parsed.outputUtf8Bytes());
        }
        if (parsed.finishReason() != null) {
            finishReason.set(safeFinishReason(parsed.finishReason()));
        }
        return Mono.fromSupplier(() -> {
            sseStarted.set(true);
            diagnosticSession.recordBoundary(
                    ApiChatDiagnosticBoundary.AFTER_BUSINESS_GATE,
                    parsed.serializedData().getBytes(StandardCharsets.UTF_8).length,
                    frameKind);
            return parsed.serializedData();
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

    private static ApiChatException protocolError(
            ApiChatProtocolViolation violation) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                "The model upstream did not provide a valid final Usage and [DONE].",
                null,
                new ApiChatProtocolViolationException(violation));
    }

    private static ApiChatFrameKind frameKind(ParsedChunk parsed) {
        if (parsed.done()) {
            return ApiChatFrameKind.DONE;
        }
        if (parsed.usage() != null) {
            return ApiChatFrameKind.USAGE;
        }
        return parsed.output() ? ApiChatFrameKind.OUTPUT : ApiChatFrameKind.DATA;
    }

    private static ApiChatFrameKind outgoingFrameKind(String data) {
        if ("[DONE]".equals(data)) {
            return ApiChatFrameKind.DONE;
        }
        return data != null && data.startsWith("{\"error\"")
                ? ApiChatFrameKind.ERROR : ApiChatFrameKind.DATA;
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

    private void count(String result) {
        meterRegistry.counter("api.chat.completion", "result", result).increment();
    }
}
