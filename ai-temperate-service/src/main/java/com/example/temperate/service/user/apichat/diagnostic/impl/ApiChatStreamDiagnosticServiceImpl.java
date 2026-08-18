package com.example.temperate.service.user.apichat.diagnostic.impl;

import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticClock;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticBoundary;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticContext;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatFrameKind;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticInvocation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticParameter;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticSession;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticStage;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnosticService;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该实现是来按采样率建立诊断会话，并通过 Reactor Context 把同一会话传递到异步流而不增加订阅、不缓存帧或改变信号顺序。
 */
@Service
public final class ApiChatStreamDiagnosticServiceImpl
        implements ApiChatStreamDiagnosticService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiChatStreamDiagnosticServiceImpl.class);
    private static final String DIAGNOSTIC_SCHEMA = "chat-diag-v1";
    private final ApiKeyProperties properties;
    private final ApiChatDiagnosticClock clock;

    @Autowired
    public ApiChatStreamDiagnosticServiceImpl(ApiKeyProperties properties) {
        this(properties, System::nanoTime);
    }

    public ApiChatStreamDiagnosticServiceImpl(
            ApiKeyProperties properties,
            ApiChatDiagnosticClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ApiChatDiagnosticInvocation enter(
            ApiChatDiagnosticStage stage,
            Object[] arguments) {
        ApiKeyProperties.StreamDiagnostics settings = properties.getStreamDiagnostics();
        boolean sampled = settings.isEnabled()
                && ThreadLocalRandom.current().nextDouble() < settings.getSampleRate();
        ApiChatDiagnosticSession session = settings.isEnabled()
                ? new ApiChatDiagnosticSession(settings, clock, sampled)
                : ApiChatDiagnosticSession.disabled();
        safeDiagnostic(() -> session.recordStageEnter(stage));
        safeDiagnostic(() -> recordSafeRequestShape(session, arguments));
        return new ApiChatDiagnosticInvocation(session, stage, clock.nanoTime());
    }

    @Override
    public void returned(ApiChatDiagnosticInvocation invocation, Object result) {
        safeDiagnostic(() -> invocation.session().recordStageReturn(
                invocation.stage(),
                Math.max(0L, clock.nanoTime() - invocation.startedNanos()),
                result));
    }

    @Override
    public void failed(ApiChatDiagnosticInvocation invocation, Throwable failure) {
        recordStageFailure(invocation, failure);
        safeDiagnostic(() -> invocation.session().recordStageFailure(invocation.stage()));
        safeDiagnostic(() -> invocation.session().recordFailure(failure));
        safeDiagnostic(() -> invocation.session().summarize(
                reactor.core.publisher.SignalType.ON_ERROR));
    }

    @Override
    public void close(ApiChatDiagnosticInvocation invocation) {
    }

    @Override
    public <T> Flux<T> observeLifecycle(
            Flux<T> source,
            ApiChatDiagnosticInvocation invocation) {
        ApiChatDiagnosticSession session = invocation.session();
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        // 所有操作符均为旁路观察；禁止在诊断代码中调用 subscribe、block、buffer 或 publishOn。
        return source
                .doOnSubscribe(ignored -> safeDiagnostic(session::recordSubscribed))
                .doOnError(failure -> {
                    recordStageFailure(invocation, failure);
                    safeDiagnostic(() -> session.recordStageFailure(invocation.stage()));
                    safeDiagnostic(() -> session.recordFailure(failure));
                })
                .doFinally(signal -> safeDiagnostic(() -> session.summarize(signal)))
                .contextWrite(context -> context.put(
                        ApiChatDiagnosticContext.SESSION_KEY, session));
    }

    @Override
    public <T> Mono<T> observeLifecycle(
            Mono<T> source,
            ApiChatDiagnosticInvocation invocation) {
        ApiChatDiagnosticSession session = invocation.session();
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        return observePreparation(source, invocation)
                .doFinally(signal -> safeDiagnostic(() -> session.summarize(signal)));
    }

    @Override
    public <T> Mono<T> observePreparation(
            Mono<T> source,
            ApiChatDiagnosticInvocation invocation) {
        ApiChatDiagnosticSession session = invocation.session();
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        // SSE 的响应头 Mono 与正文 Flux 分两次订阅；准备阶段只传递上下文和错误，不抢先关闭正文诊断。
        return source
                .doOnSubscribe(ignored -> safeDiagnostic(session::recordSubscribed))
                .doOnError(failure -> {
                    recordStageFailure(invocation, failure);
                    safeDiagnostic(() -> session.recordStageFailure(invocation.stage()));
                    safeDiagnostic(() -> session.recordFailure(failure));
                })
                .contextWrite(context -> context.put(
                        ApiChatDiagnosticContext.SESSION_KEY, session));
    }

    @Override
    public <T> Flux<T> observeBoundary(
            Flux<T> source,
            ApiChatDiagnosticBoundary boundary,
            ToLongFunction<T> byteCounter,
            Function<T, ApiChatFrameKind> kindClassifier) {
        if (!properties.getStreamDiagnostics().isEnabled()) {
            return source;
        }
        return Flux.deferContextual(context -> {
            ApiChatDiagnosticSession session =
                    ApiChatDiagnosticContext.session(context);
            if (!session.enabled()) {
                return source;
            }
            return source.doOnNext(value -> safeDiagnostic(() -> session.recordBoundary(
                    boundary,
                    Math.max(0L, byteCounter.applyAsLong(value)),
                    kindClassifier.apply(value))));
        });
    }

    private static void safeDiagnostic(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 任何旁路诊断异常都必须被隔离，不能改变同步返回值、Flux 信号或原始 Throwable。
        }
    }

    private static void recordSafeRequestShape(
            ApiChatDiagnosticSession session,
            Object[] arguments) {
        ApiKeyPrincipal principal = null;
        ApiChatRequest request = null;
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof ApiKeyPrincipal candidate) {
                    principal = candidate;
                } else if (argument instanceof ApiChatRequest candidate) {
                    request = candidate;
                }
            }
        }
        session.recordRequest(principal, request);
    }

    /**
     * 同步失败不参与成功采样；这里只记录异常分类和受控错误码，禁止记录异常消息或任何请求正文。
     */
    private void recordStageFailure(
            ApiChatDiagnosticInvocation invocation,
            Throwable failure) {
        if (!properties.getStreamDiagnostics().isEnabled() || failure == null) {
            return;
        }
        ApiChatException controlled = findControlledFailure(failure);
        try {
            LOGGER.warn(
                    "event=api_chat_stage_failure diagnosticSchema={} traceId={} stage={} elapsedMs={} exceptionType={} rootExceptionType={} apiErrorCode={} httpStatus={} parameter={} sampled={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId(invocation),
                    invocation.stage(),
                    java.time.Duration.ofNanos(Math.max(
                            0L, clock.nanoTime() - invocation.startedNanos())).toMillis(),
                    safeType(failure),
                    rootFailureType(failure),
                    controlled == null ? "none" : controlled.code().code(),
                    controlled == null ? 500 : controlled.code().status(),
                    controlled == null
                            ? "none"
                            : ApiChatDiagnosticParameter.sanitize(controlled.parameter()),
                    invocation.session().sampled());
        } catch (RuntimeException ignored) {
            // 日志后端异常不得包装、吞掉或替换原始 Throwable。
        }
    }

    private static ApiChatException findControlledFailure(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof ApiChatException controlled) {
                return controlled;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String rootFailureType(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && depth++ < 16) {
            current = current.getCause();
        }
        return safeType(current);
    }

    private static String safeType(Throwable failure) {
        String value = failure == null ? "none" : failure.getClass().getName();
        return value.matches("[A-Za-z0-9_.$]{1,200}") ? value : "unknown";
    }

    private static String traceId(ApiChatDiagnosticInvocation invocation) {
        if (invocation.session().enabled()) {
            return safeTraceId(invocation.session().traceId());
        }
        return safeTraceId(MDC.get("apiChatTraceId"));
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

}
