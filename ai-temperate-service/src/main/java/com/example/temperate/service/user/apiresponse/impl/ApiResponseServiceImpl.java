package com.example.temperate.service.user.apiresponse.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleSession;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceSettlementPendingException;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apiresponse.ApiResponseCreation;
import com.example.temperate.service.user.apiresponse.ApiResponseCreation.HttpJson;
import com.example.temperate.service.user.apiresponse.ApiResponseCreation.HttpStream;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest;
import com.example.temperate.service.user.apiresponse.ApiResponseRequestValidator;
import com.example.temperate.service.user.apiresponse.ApiResponseService;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticBoundary;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticSession;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFailureStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFrameClass;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseStreamDiagnostic;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseStreamDiagnosticService;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapterRegistry;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseJsonResult;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseProtocolParser;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamClient;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 该实现是来确保 Responses 在本地协议验证后才准入和预扣，并以权威 completed/incomplete/failed/error 终态幂等结算或退款。
 */
@Service
public final class ApiResponseServiceImpl implements ApiResponseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiResponseServiceImpl.class);
    private static final String DIAGNOSTIC_SCHEMA = "responses-diag-v1";

    private final ApiResponseRequestValidator requestValidator;
    private final ApiResponseProviderAdapterRegistry adapterRegistry;
    private final ApiResponseUpstreamClient upstreamClient;
    private final ApiResponseProtocolParser protocolParser;
    private final ApiInferenceLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ApiResponseStreamDiagnosticService diagnostics;

    public ApiResponseServiceImpl(
            ApiResponseRequestValidator requestValidator,
            ApiResponseProviderAdapterRegistry adapterRegistry,
            ApiResponseUpstreamClient upstreamClient,
            ApiResponseProtocolParser protocolParser,
            ApiInferenceLifecycleService lifecycleService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ApiResponseStreamDiagnosticService diagnostics) {
        this.requestValidator = Objects.requireNonNull(requestValidator);
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry);
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
        this.protocolParser = Objects.requireNonNull(protocolParser);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    @ApiResponseStreamDiagnostic(stage = ApiResponseDiagnosticStage.RESPONSE_SERVICE)
    public ApiResponseCreation create(
            ApiKeyPrincipal principal,
            ObjectNode request,
            String clientRequestId) {
        return createValidated(
                principal,
                requestValidator.validate(principal, request),
                clientRequestId);
    }

    @Override
    public ApiResponseCreation create(
            ApiKeyPrincipal principal,
            ApiResponseRequest request) {
        return createValidated(
                principal,
                requestValidator.validate(principal, request),
                null);
    }

    private ApiResponseCreation createValidated(
            ApiKeyPrincipal principal,
            ValidatedApiResponseRequest validated,
            String clientRequestId) {
        ApiResponseDiagnosticSession diagnosticSession = diagnostics.currentSession();
        ObjectNode payload;
        try {
            // 供应商适配仍属于纯校验阶段，必须在并发准入和 PostgreSQL 预扣之前完成。
            payload = adapterRegistry
                    .getRequired(validated.model().vendor())
                    .adapt(validated);
        } catch (ApiChatException exception) {
            diagnostics.recordFailure(
                    diagnosticSession, ApiResponseFailureStage.VALIDATION, exception);
            throw exception;
        } catch (RuntimeException exception) {
            diagnostics.recordFailure(
                    diagnosticSession, ApiResponseFailureStage.VALIDATION, exception);
            throw infrastructure("Request validation is temporarily unavailable.");
        }
        ApiInferenceLifecycleSession session;
        try {
            session = lifecycleService.start(
                    principal,
                    new ApiInferenceExecutionRequest(
                            validated.model(),
                            validated.effectiveMaxOutputTokens(),
                            validated.estimatedInputTokens(),
                            validated.stream(),
                            ApiInferenceProtocol.RESPONSES));
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(
                    diagnosticSession, ApiResponseFailureStage.RESERVATION, failure);
            throw failure;
        }
        String traceId = safeTraceId(MDC.get("apiChatTraceId"));
        log(traceId, "start", validated.stream() ? "sse" : "json", "accepted");
        ApiInferenceUpstreamRequest upstreamRequest = new ApiInferenceUpstreamRequest(
                clientRequestId,
                validated.openAiEnhanced());
        if (validated.stream()) {
            Mono<HttpStream> response = upstreamClient
                    .stream(payload, upstreamRequest)
                    .map(upstream -> new HttpStream(
                            stream(session, upstream.body(), traceId, diagnosticSession),
                            upstream.headers()))
                    .onErrorResume(failure -> failBeforeBody(session, failure))
                    .doOnCancel(() -> cancelBeforeBody(session));
            return new ApiResponseCreation.Stream(response);
        }
        return new ApiResponseCreation.Json(json(
                session, payload, upstreamRequest, traceId, diagnosticSession));
    }

    private Flux<ApiResponseSseFrame> stream(
            ApiInferenceLifecycleSession session,
            Flux<com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent>
                    upstreamBody,
            String traceId,
            ApiResponseDiagnosticSession diagnosticSession) {
        AtomicLong lastSequence = new AtomicLong(-1L);
        AtomicLong emittedBytes = new AtomicLong();
        AtomicReference<ApiInferenceUsage> usage = new AtomicReference<>();
        AtomicBoolean terminalSeen = new AtomicBoolean();
        AtomicBoolean sseStarted = new AtomicBoolean();

        Flux<ApiResponseSseFrame> parsed = lifecycleService
                .withLeaseRenewal(upstreamBody, session)
                .doOnNext(event -> diagnostics.observeBoundary(
                        diagnosticSession,
                        ApiResponseDiagnosticBoundary.UPSTREAM_RAW,
                        utf8Bytes(event.data()),
                        ApiResponseFrameClass.classify(
                                event.eventName(), TerminalKind.NONE),
                        -1L,
                        TerminalKind.NONE,
                        false))
                .doOnError(failure -> diagnostics.recordFailure(
                        diagnosticSession, ApiResponseFailureStage.UPSTREAM, failure))
                // concatMap 是 sequence_number、终态和数据库结算之间的串行化边界，禁止并行重排事件。
                .concatMap(event -> parseAndProcessSseEvent(
                        session,
                        event,
                        lastSequence,
                        emittedBytes,
                        usage,
                        terminalSeen,
                        diagnosticSession))
                .concatWith(Mono.defer(() -> terminalSeen.get()
                        ? Mono.empty()
                        : Mono.error(protocol(
                                "The model upstream ended without a Responses terminal event."))))
                // 权威终态完成本地结算或退款后立即关闭客户端流；对上游的取消会自然吞掉兼容性尾随 [DONE]。
                .takeUntil(frame -> frame.terminalKind() != TerminalKind.NONE);

        Flux<ApiResponseSseFrame> guarded = parsed.onErrorResume(failure -> {
            ApiChatException controlled = controlled(failure);
            boolean settlementPending = hasCause(
                    failure, ApiInferenceSettlementPendingException.class);
            Mono<Void> terminal = settlementPending
                    ? Mono.empty()
                    : lifecycleService.refundSystemFailure(
                            session, controlled.code().name());
            if (!sseStarted.get()) {
                return terminal.thenMany(Flux.error(controlled));
            }
            return terminal.thenMany(Flux.just(errorFrame(
                    controlled, nextSequence(lastSequence))));
        });

        return guarded
                .doOnNext(frame -> {
                    sseStarted.set(true);
                    count("sse", frame.terminalKind() == TerminalKind.NONE
                            ? eventClass(frame.eventName())
                            : frame.terminalKind().name().toLowerCase(Locale.ROOT));
                })
                .doOnError(failure -> log(
                        traceId, "terminal", "sse", controlled(failure).code().code()))
                .doFinally(signal -> finish(
                        session, signal, usage.get(), emittedBytes.get(), "sse", traceId));
    }

    private Mono<ApiResponseSseFrame> parseAndProcessSseEvent(
            ApiInferenceLifecycleSession session,
            com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent event,
            AtomicLong lastSequence,
            AtomicLong emittedBytes,
            AtomicReference<ApiInferenceUsage> usage,
            AtomicBoolean terminalSeen,
            ApiResponseDiagnosticSession diagnosticSession) {
        ApiResponseSseFrame frame;
        try {
            frame = protocolParser.parseSse(event);
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(
                    diagnosticSession, ApiResponseFailureStage.PROTOCOL_PARSE, failure);
            return Mono.error(failure);
        }
        ApiResponseFrameClass frameClass = ApiResponseFrameClass.classify(
                frame.eventName(), frame.terminalKind());
        diagnostics.observeBoundary(
                diagnosticSession,
                ApiResponseDiagnosticBoundary.AFTER_PROTOCOL_PARSE,
                frame.outputUtf8Bytes(),
                frameClass,
                frame.sequenceNumber(),
                frame.terminalKind(),
                frame.usage() != null);
        return processSseEvent(
                        session,
                        frame,
                        lastSequence,
                        emittedBytes,
                        usage,
                        terminalSeen)
                // 只有 sequence、终态和结算/退款全部成功后，事件才算通过业务 Gate。
                .doOnNext(processed -> diagnostics.observeBoundary(
                        diagnosticSession,
                        ApiResponseDiagnosticBoundary.AFTER_BUSINESS_GATE,
                        processed.outputUtf8Bytes(),
                        ApiResponseFrameClass.classify(
                                processed.eventName(), processed.terminalKind()),
                        processed.sequenceNumber(),
                        processed.terminalKind(),
                        processed.usage() != null))
                .doOnError(failure -> diagnostics.recordFailure(
                        diagnosticSession, ApiResponseFailureStage.BUSINESS_GATE, failure));
    }

    private Mono<ApiResponseSseFrame> processSseEvent(
            ApiInferenceLifecycleSession session,
            ApiResponseSseFrame frame,
            AtomicLong lastSequence,
            AtomicLong emittedBytes,
            AtomicReference<ApiInferenceUsage> usage,
            AtomicBoolean terminalSeen) {
        if (frame.terminalKind() == TerminalKind.LEGACY_DONE) {
            if (terminalSeen.get()) {
                // 8317 可能在权威 Responses 终态后兼容性追加 Chat 标记；它不得出现在客户端流中。
                return Mono.empty();
            }
            return Mono.error(protocol("[DONE] appeared before the Responses terminal event."));
        }
        if (terminalSeen.get()) {
            return Mono.error(protocol("The model upstream sent data after a terminal event."));
        }
        long previous = lastSequence.get();
        if (frame.sequenceNumber() <= previous) {
            return Mono.error(protocol("Responses sequence_number is not strictly increasing."));
        }
        lastSequence.set(frame.sequenceNumber());
        emittedBytes.addAndGet(frame.outputUtf8Bytes());

        return switch (frame.terminalKind()) {
            case NONE -> Mono.just(frame);
            case COMPLETED, INCOMPLETE -> {
                terminalSeen.set(true);
                usage.set(frame.usage());
                yield lifecycleService.settle(
                                session, frame.usage(), frame.finishReason())
                        .thenReturn(frame);
            }
            case FAILED, ERROR -> {
                terminalSeen.set(true);
                yield lifecycleService.refundSystemFailure(
                                session, frame.terminalKind().name())
                        .thenReturn(frame);
            }
            case LEGACY_DONE -> Mono.empty();
        };
    }

    private Mono<HttpJson> json(
            ApiInferenceLifecycleSession session,
            ObjectNode payload,
            ApiInferenceUpstreamRequest upstreamRequest,
            String traceId,
            ApiResponseDiagnosticSession diagnosticSession) {
        AtomicReference<ApiInferenceUsage> usage = new AtomicReference<>();
        Mono<HttpJson> source = lifecycleService
                .withLeaseRenewal(
                        upstreamClient.create(payload, upstreamRequest).flux(), session)
                .doOnNext(ignored -> diagnostics.observeBoundary(
                        diagnosticSession,
                        ApiResponseDiagnosticBoundary.UPSTREAM_RAW,
                        0L,
                        ApiResponseFrameClass.LIFECYCLE,
                        -1L,
                        TerminalKind.NONE,
                        false))
                .doOnError(failure -> diagnostics.recordFailure(
                        diagnosticSession, ApiResponseFailureStage.UPSTREAM, failure))
                .single()
                .map(response -> new ParsedResponseJson(
                        parseJson(response.body(), diagnosticSession), response))
                .flatMap(parsed -> processJsonResult(session, parsed.result(), usage)
                        .map(body -> new HttpJson(body, parsed.upstream().headers()))
                        .doOnSuccess(ignored -> diagnostics.observeBoundary(
                                diagnosticSession,
                                ApiResponseDiagnosticBoundary.AFTER_BUSINESS_GATE,
                                0L,
                                ApiResponseFrameClass.TERMINAL,
                                -1L,
                                jsonTerminal(parsed.result()),
                                parsed.result().usage() != null))
                        .doOnError(failure -> diagnostics.recordFailure(
                                diagnosticSession,
                                ApiResponseFailureStage.BUSINESS_GATE,
                                failure)))
                .onErrorResume(failure -> {
                    if (hasCause(failure, ApiInferenceSettlementPendingException.class)) {
                        return Mono.error(failure);
                    }
                    return lifecycleService.refundSystemFailure(
                                    session, failureCode(failure))
                            .then(Mono.error(failure));
                });
        return source
                .doOnSuccess(ignored -> count("json", "completed"))
                .doOnError(failure -> log(
                        traceId, "terminal", "json", controlled(failure).code().code()))
                .doFinally(signal -> finish(
                        session, signal, usage.get(), 0L, "json", traceId));
    }

    private <T> Mono<T> failBeforeBody(
            ApiInferenceLifecycleSession session,
            Throwable failure) {
        Mono<Void> terminal = hasCause(
                failure, ApiInferenceSettlementPendingException.class)
                ? Mono.empty()
                : lifecycleService.refundSystemFailure(
                        session, failureCode(failure));
        return terminal.then(Mono.<T>error(failure))
                .doFinally(signal -> lifecycleService.release(session));
    }

    private void cancelBeforeBody(ApiInferenceLifecycleSession session) {
        // 响应头前取消不会订阅 SSE 正文，必须由准备阶段主动释放并交给统一取消补偿收敛账单。
        lifecycleService.release(session);
        lifecycleService.scheduleCancellation(session, null, 0L);
    }

    private static String failureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiChatException controlled) {
                return controlled.code().name();
            }
            current = current.getCause();
        }
        return ApiChatErrorCode.UPSTREAM_UNAVAILABLE.name();
    }

    private ApiResponseJsonResult parseJson(
            JsonNode response,
            ApiResponseDiagnosticSession diagnosticSession) {
        try {
            ApiResponseJsonResult result = protocolParser.parseJson(response);
            diagnostics.observeBoundary(
                    diagnosticSession,
                    ApiResponseDiagnosticBoundary.AFTER_PROTOCOL_PARSE,
                    0L,
                    ApiResponseFrameClass.TERMINAL,
                    -1L,
                    jsonTerminal(result),
                    result.usage() != null);
            return result;
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(
                    diagnosticSession, ApiResponseFailureStage.PROTOCOL_PARSE, failure);
            throw failure;
        }
    }

    private static TerminalKind jsonTerminal(ApiResponseJsonResult result) {
        return switch (result.status()) {
            case COMPLETED -> TerminalKind.COMPLETED;
            case INCOMPLETE -> TerminalKind.INCOMPLETE;
            case FAILED -> TerminalKind.FAILED;
        };
    }

    private Mono<JsonNode> processJsonResult(
            ApiInferenceLifecycleSession session,
            ApiResponseJsonResult result,
            AtomicReference<ApiInferenceUsage> usage) {
        return switch (result.status()) {
            case COMPLETED, INCOMPLETE -> {
                usage.set(result.usage());
                // HTTP 200 只能在本地实际结算完成后提交，避免输出成功但账单仍为 RESERVED。
                yield lifecycleService.settle(
                                session, result.usage(), result.finishReason())
                        .thenReturn(result.response());
            }
            case FAILED -> lifecycleService.refundSystemFailure(session, "FAILED")
                    // failed 仍是合法 Responses 终态；退款完成后必须保留原始对象供 SDK 读取 error 字段。
                    .thenReturn(result.response());
        };
    }

    private void finish(
            ApiInferenceLifecycleSession session,
            SignalType signal,
            ApiInferenceUsage usage,
            long emittedBytes,
            String mode,
            String traceId) {
        lifecycleService.release(session);
        if (signal == SignalType.CANCEL) {
            lifecycleService.scheduleCancellation(session, usage, emittedBytes);
            count(mode, "client_cancelled");
        }
        logFinish(
                traceId,
                mode,
                signal.name().toLowerCase(Locale.ROOT),
                usage != null,
                session.terminalState().name().toLowerCase(Locale.ROOT),
                emittedBytes);
    }

    private ApiResponseSseFrame errorFrame(ApiChatException exception, long sequence) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");
        error.put("sequence_number", sequence);
        error.put("code", exception.code().code());
        error.put("message", exception.getMessage());
        if (exception.parameter() == null) {
            error.putNull("param");
        } else {
            error.put("param", exception.parameter());
        }
        try {
            return new ApiResponseSseFrame(
                    "error",
                    objectMapper.writeValueAsString(error),
                    0L,
                    sequence,
                    TerminalKind.ERROR,
                    null,
                    exception.code().code().toUpperCase(Locale.ROOT));
        } catch (JsonProcessingException serializationFailure) {
            return new ApiResponseSseFrame(
                    "error",
                    "{\"type\":\"error\",\"code\":\"streaming_failed\","
                            + "\"message\":\"Streaming failed.\",\"param\":null,"
                            + "\"sequence_number\":" + sequence + "}",
                    0L,
                    sequence,
                    TerminalKind.ERROR,
                    null,
                    "STREAMING_FAILED");
        }
    }

    private static long nextSequence(AtomicLong lastSequence) {
        long current = lastSequence.get();
        return current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
    }

    private static long utf8Bytes(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String eventClass(String eventName) {
        if (eventName == null) {
            return "unknown_event";
        }
        if (eventName.startsWith("response.output_text.")) {
            return "output_text";
        }
        if (eventName.startsWith("response.reasoning")) {
            return "reasoning";
        }
        if (eventName.startsWith("response.function_call_arguments.")) {
            return "function_arguments";
        }
        if (eventName.startsWith("response.output_item.")) {
            return "output_item";
        }
        return "other_event";
    }

    private void count(String mode, String result) {
        meterRegistry.counter(
                "api.responses",
                "mode", mode,
                "result", result).increment();
    }

    private static void log(
            String traceId,
            String stage,
            String mode,
            String result) {
        try {
            LOGGER.info(
                    "event=api_responses_lifecycle diagnosticSchema={} traceId={} stage={} mode={} result={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    stage,
                    mode,
                    result);
        } catch (RuntimeException ignored) {
            // 诊断后端故障不能改变推理流信号或账单终态。
        }
    }

    private static void logFinish(
            String traceId,
            String mode,
            String result,
            boolean usagePresent,
            String terminal,
            long outputBytes) {
        try {
            LOGGER.info(
                    "event=api_responses_lifecycle diagnosticSchema={} traceId={} stage=finish mode={} result={} terminal={} usagePresent={} outputBytes={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    result,
                    terminal,
                    usagePresent,
                    Math.max(0L, outputBytes));
        } catch (RuntimeException ignored) {
            // 诊断后端故障不能改变推理流信号或账单终态。
        }
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static ApiChatException controlled(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiChatException controlled) {
                return controlled;
            }
            current = current.getCause();
        }
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                "The model upstream is unavailable.",
                null);
    }

    private static boolean hasCause(
            Throwable failure,
            Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ApiChatException protocol(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                message,
                null);
    }

    private static ApiChatException infrastructure(String message) {
        return new ApiChatException(
                ApiChatErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                message,
                null);
    }

    /** 该内部结果确保 Responses 非流式协议事实和安全头来自同一次上游交换。 */
    private record ParsedResponseJson(
            ApiResponseJsonResult result,
            ApiResponseUpstreamJson upstream) {
    }
}
