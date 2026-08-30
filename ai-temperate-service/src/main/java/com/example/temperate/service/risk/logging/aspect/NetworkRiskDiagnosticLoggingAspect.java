package com.example.temperate.service.risk.logging.aspect;

import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.observability.NetworkRiskDiagnosticContext;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 记录 PreAuth 状态转换与网络风险评估的非敏感诊断事件，用于关联同一请求的 428 产生位置。
 *
 * <p>切面只读取作用域、认证状态、会话类型、决策、受控异常类别和耗时；禁止记录方法中的原始
 * Token、会话引用、Cookie、IP、设备标识、摘要、Redis Key、异常消息或 cause 内容。</p>
 */
@Aspect
@Component
public final class NetworkRiskDiagnosticLoggingAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NetworkRiskDiagnosticLoggingAspect.class);
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE_DIAGNOSTIC_VALUE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /**
     * 记录 PreAuth 解析是否命中；返回对象仅用于提取有限枚举状态，不输出访问凭据或摘要。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.service."
                    + "PreAuthService.resolve(..))")
    public Object logResolve(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        RiskScope requestedScope = argument(joinPoint, 0, RiskScope.class);
        try {
            Object result = joinPoint.proceed();
            Optional<?> resolved = result instanceof Optional<?> optional
                    ? optional
                    : Optional.empty();
            PreAuthAccess access = resolved
                    .filter(PreAuthAccess.class::isInstance)
                    .map(PreAuthAccess.class::cast)
                    .orElse(null);
            logPreAuthCompleted(
                    "preauth_resolve_completed",
                    access == null ? requestedScope : scope(access),
                    access,
                    access == null ? "not_found" : "found",
                    startedAtNanos,
                    null,
                    false);
            return result;
        } catch (Throwable failure) {
            logPreAuthCompleted(
                    "preauth_resolve_completed",
                    requestedScope,
                    null,
                    "failed",
                    startedAtNanos,
                    failure,
                    true);
            throw failure;
        }
    }

    /**
     * 记录滑动续期的原子结果；未命中使用 WARN，便于默认 INFO 配置直接观察并发过期。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.service."
                    + "PreAuthService.touch(..))")
    public Object logTouch(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        try {
            Object result = joinPoint.proceed();
            boolean succeeded = Boolean.TRUE.equals(result);
            logPreAuthCompleted(
                    "preauth_touch_completed",
                    scope(access),
                    access,
                    succeeded ? "succeeded" : "not_found",
                    startedAtNanos,
                    null,
                    !succeeded);
            return result;
        } catch (Throwable failure) {
            logPreAuthCompleted(
                    "preauth_touch_completed",
                    scope(access),
                    access,
                    "failed",
                    startedAtNanos,
                    failure,
                    true);
            throw failure;
        }
    }

    /**
     * 记录匿名 PreAuth 晋升到用户或管理员会话的边界，但不读取或输出会话引用及新 Token。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.service."
                    + "PreAuthService.promoteAuthenticated(..))")
    public Object logPromotion(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        RiskSessionType targetSessionType =
                argument(joinPoint, 1, RiskSessionType.class);
        NetworkRiskDiagnosticContext.Snapshot context =
                NetworkRiskDiagnosticContext.current();
        LOGGER.debug(
                "event=preauth_promotion_started traceId={} invocationNo={} "
                        + "dispatcherType={} phase={} scope={} authState={} "
                        + "sessionType={} targetSessionType={}",
                context.traceId(),
                context.invocationNo(),
                context.dispatcherType(),
                context.phase(),
                safeScope(scope(access)),
                authState(access),
                sessionType(access),
                safeSessionType(targetSessionType));
        try {
            Object result = joinPoint.proceed();
            logPreAuthCompleted(
                    "preauth_promotion_completed",
                    scope(access),
                    access,
                    result == null ? "empty" : "succeeded",
                    startedAtNanos,
                    null,
                    result == null);
            return result;
        } catch (Throwable failure) {
            logPreAuthCompleted(
                    "preauth_promotion_completed",
                    scope(access),
                    access,
                    "failed",
                    startedAtNanos,
                    failure,
                    true);
            throw failure;
        }
    }

    /**
     * 记录 PreAuth 与刷新会话的绑定复核结果；原始会话引用只交给业务服务，切面禁止读取或格式化该参数。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.service."
                    + "PreAuthService.requireSessionBinding(..))")
    public Object logSessionBinding(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        RiskScope requestedScope = argument(joinPoint, 1, RiskScope.class);
        try {
            Object result = joinPoint.proceed();
            logPreAuthCompleted(
                    "preauth_session_binding_completed",
                    requestedScope == null ? scope(access) : requestedScope,
                    access,
                    result == null ? "empty" : "succeeded",
                    startedAtNanos,
                    null,
                    result == null);
            return result;
        } catch (Throwable failure) {
            logPreAuthCompleted(
                    "preauth_session_binding_completed",
                    requestedScope == null ? scope(access) : requestedScope,
                    access,
                    failure instanceof IllegalArgumentException
                            ? "preauth_required"
                            : "failed",
                    startedAtNanos,
                    failure,
                    true);
            throw failure;
        }
    }

    /**
     * 使用 Mono.defer 把开始与完成日志延迟到真实订阅，并用原子门禁保证每次订阅只有一个终态事件。
     */
    @Around(
            "execution(reactor.core.publisher.Mono "
                    + "com.example.temperate.service.risk.decision."
                    + "NetworkRiskAssessmentService.assess(..))")
    public Object logAssessment(ProceedingJoinPoint joinPoint) throws Throwable {
        long invocationStartedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        String assessmentScope = safeScope(scope(access));
        String assessmentAuthState = authState(access);
        String assessmentSessionType = sessionType(access);
        final Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            logAssessmentCompleted(
                    new AtomicBoolean(),
                    NetworkRiskDiagnosticContext.current(),
                    assessmentScope,
                    assessmentAuthState,
                    assessmentSessionType,
                    failure instanceof PreAuthRequiredException
                            ? "preauth_required"
                            : "failed",
                    UNAVAILABLE,
                    failure,
                    invocationStartedAtNanos,
                    true);
            throw failure;
        }
        if (!(result instanceof Mono<?> operation)) {
            return result;
        }
        @SuppressWarnings("unchecked")
        Mono<RiskAssessment> assessment = (Mono<RiskAssessment>) operation;
        return Mono.defer(() -> {
            NetworkRiskDiagnosticContext.Snapshot context =
                    NetworkRiskDiagnosticContext.current();
            long startedAtNanos = System.nanoTime();
            AtomicBoolean completed = new AtomicBoolean();
            LOGGER.debug(
                    "event=network_risk_assessment_started traceId={} invocationNo={} "
                            + "dispatcherType={} phase={} scope={} authState={} sessionType={}",
                    context.traceId(),
                    context.invocationNo(),
                    context.dispatcherType(),
                    context.phase(),
                    assessmentScope,
                    assessmentAuthState,
                    assessmentSessionType);
            return assessment
                    .doOnSuccess(value -> logAssessmentCompleted(
                            completed,
                            context,
                            assessmentScope,
                            assessmentAuthState,
                            assessmentSessionType,
                            value == null ? "empty" : "succeeded",
                            value == null || value.decision() == null
                                    ? UNAVAILABLE
                                    : value.decision().name(),
                            null,
                            startedAtNanos,
                            value == null))
                    .doOnError(failure -> logAssessmentCompleted(
                            completed,
                            context,
                            assessmentScope,
                            assessmentAuthState,
                            assessmentSessionType,
                            failure instanceof PreAuthRequiredException
                                    ? "preauth_required"
                                    : "failed",
                            UNAVAILABLE,
                            failure,
                            startedAtNanos,
                            true))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            logAssessmentCompleted(
                                    completed,
                                    context,
                                    assessmentScope,
                                    assessmentAuthState,
                                    assessmentSessionType,
                                    "cancelled",
                                    UNAVAILABLE,
                                    null,
                                    startedAtNanos,
                                    true);
                        }
                    });
        });
    }

    private static void logPreAuthCompleted(
            String event,
            RiskScope scope,
            PreAuthAccess access,
            String result,
            long startedAtNanos,
            Throwable failure,
            boolean warn) {
        NetworkRiskDiagnosticContext.Snapshot context =
                NetworkRiskDiagnosticContext.current();
        String template = "event={} traceId={} invocationNo={} dispatcherType={} phase={} "
                + "scope={} authState={} sessionType={} result={} exceptionClass={} durationMs={}";
        Object[] arguments = {
            event,
            context.traceId(),
            context.invocationNo(),
            context.dispatcherType(),
            context.phase(),
            safeScope(scope),
            authState(access),
            sessionType(access),
            result,
            exceptionClass(failure),
            elapsedMillis(startedAtNanos)
        };
        if (warn) {
            LOGGER.warn(template, arguments);
            return;
        }
        LOGGER.debug(template, arguments);
    }

    private static void logAssessmentCompleted(
            AtomicBoolean completed,
            NetworkRiskDiagnosticContext.Snapshot context,
            String scope,
            String authState,
            String sessionType,
            String outcome,
            String decision,
            Throwable failure,
            long startedAtNanos,
            boolean warn) {
        // Reactor 的成功、错误和取消信号可能在边界竞争；这里只允许输出一个完成事件。
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        String template = "event=network_risk_assessment_completed traceId={} invocationNo={} "
                + "dispatcherType={} phase={} scope={} authState={} sessionType={} outcome={} "
                + "decision={} exceptionClass={} durationMs={}";
        Object[] arguments = {
            context.traceId(),
            context.invocationNo(),
            context.dispatcherType(),
            context.phase(),
            scope,
            authState,
            sessionType,
            outcome,
            decision,
            exceptionClass(failure),
            elapsedMillis(startedAtNanos)
        };
        if (warn) {
            LOGGER.warn(template, arguments);
            return;
        }
        LOGGER.debug(template, arguments);
    }

    private static RiskScope scope(PreAuthAccess access) {
        return access == null || access.state() == null
                ? null
                : access.state().scope();
    }

    private static String authState(PreAuthAccess access) {
        String value = access == null || access.state() == null
                ? null
                : access.state().authState();
        return safeDiagnosticValue(value);
    }

    private static String sessionType(PreAuthAccess access) {
        RiskSessionType value = access == null || access.state() == null
                ? null
                : access.state().sessionType();
        return safeSessionType(value);
    }

    private static String safeScope(RiskScope scope) {
        return scope == null ? UNAVAILABLE : scope.name();
    }

    private static String safeSessionType(RiskSessionType sessionType) {
        return sessionType == null ? UNAVAILABLE : sessionType.name();
    }

    private static String exceptionClass(Throwable failure) {
        return failure == null
                ? UNAVAILABLE
                : safeDiagnosticValue(failure.getClass().getSimpleName());
    }

    private static String safeDiagnosticValue(String value) {
        if (value == null || value.isBlank()) {
            return UNAVAILABLE;
        }
        return SAFE_DIAGNOSTIC_VALUE.matcher(value).matches()
                ? value
                : UNAVAILABLE;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static <T> T argument(
            ProceedingJoinPoint joinPoint,
            int index,
            Class<T> expectedType) {
        Object[] arguments = joinPoint.getArgs();
        if (arguments == null || index < 0 || index >= arguments.length) {
            return null;
        }
        Object value = arguments[index];
        return expectedType.isInstance(value) ? expectedType.cast(value) : null;
    }
}
