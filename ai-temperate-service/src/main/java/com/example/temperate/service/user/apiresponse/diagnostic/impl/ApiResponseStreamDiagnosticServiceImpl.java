package com.example.temperate.service.user.apiresponse.diagnostic.impl;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticBoundary;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticClock;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticInvocation;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticSession;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFailureStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFrameClass;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseStreamDiagnosticService;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 该实现是来按现有 API Key 诊断配置创建 Responses 会话，并用同步 ThreadLocal 复用嵌套 AOP、用捕获对象观察异步信号。
 * ThreadLocal 只存在于方法装配期间；异步回调不读取线程状态，也不增加订阅、缓存或调度边界。
 */
@Service
public final class ApiResponseStreamDiagnosticServiceImpl
        implements ApiResponseStreamDiagnosticService {

    private final ApiKeyProperties properties;
    private final ApiResponseDiagnosticClock clock;
    private final ThreadLocal<SessionContext> current = new ThreadLocal<>();

    @Autowired
    public ApiResponseStreamDiagnosticServiceImpl(ApiKeyProperties properties) {
        this(properties, System::nanoTime);
    }

    public ApiResponseStreamDiagnosticServiceImpl(
            ApiKeyProperties properties,
            ApiResponseDiagnosticClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ApiResponseDiagnosticInvocation enter(
            ApiResponseDiagnosticStage stage,
            Object[] arguments) {
        SessionContext context = current.get();
        boolean owner = context == null;
        if (owner) {
            ApiKeyProperties.StreamDiagnostics settings = properties.getStreamDiagnostics();
            boolean sampled = settings.isEnabled()
                    && ThreadLocalRandom.current().nextDouble() < settings.getSampleRate();
            ApiResponseDiagnosticSession session = settings.isEnabled()
                    ? new ApiResponseDiagnosticSession(
                            settings, clock, sampled, mode(arguments))
                    : ApiResponseDiagnosticSession.disabled();
            context = new SessionContext(session);
            current.set(context);
        }
        context.depth++;
        SessionContext activeContext = context;
        ApiResponseDiagnosticInvocation invocation = new ApiResponseDiagnosticInvocation(
                activeContext.session, stage, clock.nanoTime(), owner);
        safeDiagnostic(() -> activeContext.session.recordStageEnter(stage));
        return invocation;
    }

    @Override
    public void returned(ApiResponseDiagnosticInvocation invocation, Object result) {
        safeDiagnostic(() -> invocation.session().recordStageReturn(
                invocation.stage(),
                Math.max(0L, clock.nanoTime() - invocation.startedNanos()),
                result));
    }

    @Override
    public void failed(ApiResponseDiagnosticInvocation invocation, Throwable failure) {
        safeDiagnostic(() -> invocation.session().recordFailure(
                stageFailure(invocation.stage()), failure));
        if (invocation.owner()) {
            safeDiagnostic(() -> invocation.session().summarize(
                    SignalType.ON_ERROR, false));
        }
    }

    @Override
    public void close(ApiResponseDiagnosticInvocation invocation) {
        SessionContext context = current.get();
        if (context == null || context.session != invocation.session()) {
            return;
        }
        context.depth--;
        if (context.depth <= 0) {
            current.remove();
        }
    }

    @Override
    public <T> Flux<T> observeLifecycle(
            Flux<T> source,
            ApiResponseDiagnosticInvocation invocation) {
        Objects.requireNonNull(source);
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        ApiResponseDiagnosticSession session = invocation.session();
        // 这些操作符只旁路观察既有信号；禁止在诊断服务里 subscribe、block、buffer、publishOn 或改变 request(n)。
        return source
                .doOnSubscribe(ignored -> safeDiagnostic(session::recordSubscribed))
                .doOnError(failure -> safeDiagnostic(() -> session.recordFailure(
                        stageFailure(invocation.stage()), failure)))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        safeDiagnostic(session::recordClientCancelled);
                    }
                    if (invocation.owner()) {
                        safeDiagnostic(() -> session.summarize(signal, false));
                    }
                });
    }

    @Override
    public <T> Mono<T> observeLifecycle(
            Mono<T> source,
            ApiResponseDiagnosticInvocation invocation) {
        Objects.requireNonNull(source);
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        ApiResponseDiagnosticSession session = invocation.session();
        return source
                .doOnSubscribe(ignored -> safeDiagnostic(session::recordSubscribed))
                .doOnError(failure -> safeDiagnostic(() -> session.recordFailure(
                        stageFailure(invocation.stage()), failure)))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        safeDiagnostic(session::recordClientCancelled);
                    }
                    // SSE 的 Controller Mono 只表示 200 与 Body 已准备，真正终态必须由 Gate 总结。
                    boolean shouldSummarize = invocation.owner()
                            && (!"sse".equals(session.mode())
                                    || signal == SignalType.ON_ERROR
                                    || signal == SignalType.CANCEL);
                    if (shouldSummarize) {
                        safeDiagnostic(() -> session.summarize(signal, false));
                    }
                });
    }

    @Override
    public void observeBoundary(
            ApiResponseDiagnosticSession session,
            ApiResponseDiagnosticBoundary boundary,
            long bytes,
            ApiResponseFrameClass frameClass,
            long sequence,
            TerminalKind terminalKind,
            boolean usagePresent) {
        safeDiagnostic(() -> session.recordBoundary(
                boundary,
                bytes,
                frameClass,
                sequence,
                terminalKind,
                usagePresent));
    }

    @Override
    public void recordFailure(
            ApiResponseDiagnosticSession session,
            ApiResponseFailureStage stage,
            Throwable failure) {
        safeDiagnostic(() -> session.recordFailure(stage, failure));
    }

    @Override
    public ApiResponseDiagnosticSession currentSession() {
        SessionContext context = current.get();
        return context == null
                ? ApiResponseDiagnosticSession.disabled()
                : context.session;
    }

    private static String mode(Object[] arguments) {
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof ApiResponseRequest request) {
                    if (request.stream() == null || request.stream().isNull()) {
                        return "json";
                    }
                    if (request.stream().isBoolean()) {
                        return request.stream().booleanValue() ? "sse" : "json";
                    }
                    return "unknown";
                } else if (argument instanceof ObjectNode request) {
                    JsonNode stream = request.get("stream");
                    if (stream == null || stream.isNull()) {
                        return "json";
                    }
                    return stream.isBoolean()
                            ? (stream.booleanValue() ? "sse" : "json")
                            : "unknown";
                }
            }
        }
        return "unknown";
    }

    private static ApiResponseFailureStage stageFailure(
            ApiResponseDiagnosticStage stage) {
        return stage == ApiResponseDiagnosticStage.HTTP_CONTROLLER
                ? ApiResponseFailureStage.ERROR_MAPPING
                : ApiResponseFailureStage.UNKNOWN;
    }

    private static void safeDiagnostic(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 旁路诊断异常不能改变同步返回值、Publisher 信号、背压或原始 Throwable。
        }
    }

    private static final class SessionContext {
        private final ApiResponseDiagnosticSession session;
        private int depth;

        private SessionContext(ApiResponseDiagnosticSession session) {
            this.session = session;
        }
    }
}
