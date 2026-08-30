package com.example.temperate.service.auth.session.diagnostic;

import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 记录普通受保护请求认证和 H5 Bootstrap 会话恢复的服务层耗时与脱敏结果。
 *
 * <p>切面只观察返回类型、续签布尔值、是否提交服务端 PreAuth 绑定、受控错误码和异常类；禁止读取、格式化或
 * 输出命令对象中的 AT、RT、CSRF、设备标识以及返回对象中的新凭据。</p>
 */
@Aspect
@Component
public final class SessionAuthenticationDiagnosticAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SessionAuthenticationDiagnosticAspect.class);
    private static final Pattern SAFE_VALUE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /**
     * 记录普通 API RT-first 认证服务边界，不区分重载中的原始命令内容。
     */
    @Around(
            "execution(* com.example.temperate.service.auth.session.access."
                    + "AccessSessionService.authenticateOrRenew(..))")
    public Object logAccessAuthentication(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        boolean bindingPresented = bindingPresented(joinPoint);
        try {
            Object result = joinPoint.proceed();
            SessionAccessResult accessResult = result instanceof SessionAccessResult value
                    ? value
                    : null;
            log(
                    "session_access_service_completed",
                    accessResult == null ? "empty" : "succeeded",
                    accessResult != null && accessResult.renewed(),
                    bindingPresented,
                    null,
                    startedAtNanos,
                    accessResult == null);
            return result;
        } catch (Throwable failure) {
            log(
                    "session_access_service_completed",
                    "failed",
                    false,
                    bindingPresented,
                    failure,
                    startedAtNanos,
                    true);
            throw failure;
        }
    }

    /**
     * 记录 H5 Bootstrap 服务边界，返回凭据只用于判定结果对象是否存在，禁止进入日志参数。
     */
    @Around(
            "execution(* com.example.temperate.service.auth.session.authentication.service."
                    + "SessionAuthenticationService.bootstrap(..))")
    public Object logBootstrap(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        boolean bindingPresented = bindingPresented(joinPoint);
        try {
            Object result = joinPoint.proceed();
            boolean succeeded = result instanceof SessionAuthenticationResult;
            log(
                    "session_bootstrap_service_completed",
                    succeeded ? "succeeded" : "empty",
                    false,
                    bindingPresented,
                    null,
                    startedAtNanos,
                    !succeeded);
            return result;
        } catch (Throwable failure) {
            log(
                    "session_bootstrap_service_completed",
                    "failed",
                    false,
                    bindingPresented,
                    failure,
                    startedAtNanos,
                    true);
            throw failure;
        }
    }

    private static void log(
            String event,
            String outcome,
            boolean renewed,
            boolean bindingPresented,
            Throwable failure,
            long startedAtNanos,
            boolean warn) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        String template = "event={} traceId={} outcome={} renewed={} bindingPresented={} "
                + "errorCode={} exceptionClass={} durationMs={}";
        Object[] arguments = {
            event,
            safeValue(traceId),
            outcome,
            renewed,
            bindingPresented,
            errorCode(failure),
            failure == null ? "unavailable" : safeValue(failure.getClass().getSimpleName()),
            elapsedMillis(startedAtNanos)
        };
        if (warn) {
            LOGGER.warn(template, arguments);
            return;
        }
        LOGGER.info(template, arguments);
    }

    private static boolean bindingPresented(ProceedingJoinPoint joinPoint) {
        Object[] arguments = joinPoint.getArgs();
        return arguments != null
                && arguments.length > 1
                && arguments[1] instanceof PreAuthSessionBinding;
    }

    private static String errorCode(Throwable failure) {
        return failure instanceof SessionAuthenticationException exception
                ? exception.code().name()
                : "unavailable";
    }

    private static String safeValue(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches()
                ? value
                : "unavailable";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
