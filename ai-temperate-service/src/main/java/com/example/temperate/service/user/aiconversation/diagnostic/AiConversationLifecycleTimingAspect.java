package com.example.temperate.service.user.aiconversation.diagnostic;

import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 对标记的公开业务方法记录固定生命周期阶段，并保持 Reactor 返回值的惰性和原始取消语义。
 */
@Aspect
@Component
@ConditionalOnProperty(
        prefix = "app.ai-conversation.lifecycle-diagnostics",
        name = "enabled",
        havingValue = "true")
public final class AiConversationLifecycleTimingAspect {

    private final AiConversationLifecycleDiagnosticService diagnostics;

    public AiConversationLifecycleTimingAspect(
            AiConversationLifecycleDiagnosticService diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    /**
     * 同步方法在调用栈内计时，Flux 与 Mono 只附加终态观察而不创建内部订阅。
     *
     * @param joinPoint 被标记的公开 Spring Bean 方法
     * @param timed 固定阶段注解
     * @return 原始值或保持惰性的 Reactor 包装
     * @throws Throwable 原方法异常
     */
    @Around("@annotation(timed)")
    public Object observe(
            ProceedingJoinPoint joinPoint,
            AiConversationLifecycleTimed timed) throws Throwable {
        String stage = normalizedStage(timed.stage());
        AiConversationLifecycleTraceContext context =
                diagnostics.currentContext();
        long startedNanos = System.nanoTime();
        diagnostics.record(context, stage + "_ENTERED");
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            diagnostics.record(
                    context,
                    stage + "_FAILED",
                    failureEvent(failure, startedNanos));
            throw failure;
        }
        if (result instanceof Flux<?> flux) {
            return observeFlux(flux, context, stage, startedNanos);
        }
        if (result instanceof Mono<?> mono) {
            return observeMono(mono, context, stage, startedNanos);
        }
        diagnostics.record(
                context,
                stage + "_COMPLETED",
                AiConversationLifecycleEvent.timing(
                        elapsedMillis(startedNanos)));
        return result;
    }

    private Flux<?> observeFlux(
            Flux<?> source,
            AiConversationLifecycleTraceContext context,
            String stage,
            long startedNanos) {
        return source.transformDeferred(flux -> {
            AtomicBoolean terminal = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return flux.doOnError(error -> failure.compareAndSet(null, error))
                    .doFinally(signal -> {
                        if (terminal.compareAndSet(false, true)) {
                            diagnostics.record(
                                    context,
                                    stage + terminalSuffix(signal.name()),
                                    failure.get() == null
                                            ? AiConversationLifecycleEvent.timing(
                                                    elapsedMillis(startedNanos))
                                            : failureEvent(
                                                    failure.get(), startedNanos));
                        }
                    });
        });
    }

    private Mono<?> observeMono(
            Mono<?> source,
            AiConversationLifecycleTraceContext context,
            String stage,
            long startedNanos) {
        return source.transformDeferred(mono -> {
            AtomicBoolean terminal = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return mono.doOnError(error -> failure.compareAndSet(null, error))
                    .doFinally(signal -> {
                        if (terminal.compareAndSet(false, true)) {
                            diagnostics.record(
                                    context,
                                    stage + terminalSuffix(signal.name()),
                                    failure.get() == null
                                            ? AiConversationLifecycleEvent.timing(
                                                    elapsedMillis(startedNanos))
                                            : failureEvent(
                                                    failure.get(), startedNanos));
                        }
                    });
        });
    }

    private static String terminalSuffix(String signal) {
        return switch (signal) {
            case "CANCEL" -> "_CANCELLED";
            case "ON_ERROR" -> "_FAILED";
            default -> "_COMPLETED";
        };
    }

    private static String normalizedStage(String stage) {
        String normalized = Objects.requireNonNullElse(stage, "")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException(
                    "AI lifecycle diagnostic stage is invalid.");
        }
        return normalized;
    }

    private static AiConversationLifecycleEvent failureEvent(
            Throwable failure,
            long startedNanos) {
        if (failure instanceof AiConversationException controlled) {
            return AiConversationLifecycleEvent.failure(
                    "CONTROLLED_FAILURE",
                    controlled.code().name(),
                    elapsedMillis(startedNanos));
        }
        return AiConversationLifecycleEvent.failure(
                "UNCONTROLLED_EXCEPTION",
                "UNCONTROLLED_EXCEPTION",
                elapsedMillis(startedNanos));
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
