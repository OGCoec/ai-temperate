package com.example.temperate.service.auth.oauth.diagnostic;

import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 精确观察 OAuth Provider 完成入口，并在账号解析失败时记录不含凭据的根异常元数据。
 *
 * <p>切面只读取可信身份中的 Provider 枚举和线程内固定阶段；不读取、格式化或记录邮箱、Subject、
 * Proof、Flow ID、异常消息或堆栈，原始异常始终原样抛回调用方。</p>
 */
@Aspect
@Component
public final class OAuthAccountResolutionDiagnosticAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            OAuthAccountResolutionDiagnosticAspect.class);
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final OAuthAccountResolutionFailureClassifier failureClassifier;

    public OAuthAccountResolutionDiagnosticAspect(
            OAuthAccountResolutionFailureClassifier failureClassifier) {
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    /**
     * 为一次Provider完成调用建立诊断作用域；失败日志不携带Throwable，防止Logback输出敏感消息。
     */
    @Around(
            "execution(* com.example.temperate.service.auth.oauth.identity."
                    + "OAuthProviderCompletionService+.accept(..))")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        String provider = provider(joinPoint);
        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            try {
                return joinPoint.proceed();
            } catch (Throwable failure) {
                OAuthAccountResolutionFailureClassifier.Classification classification =
                        failureClassifier.classify(failure);
                LOGGER.warn(
                        "event=oauth_account_resolution_failed "
                                + "diagnosticSchema=oauth-account-resolution-v1 "
                                + "traceId={} provider={} stage={} failureCategory={} "
                                + "exceptionClass={} rootExceptionClass={} "
                                + "rootSourceClass={} rootSourceMethod={} sqlState={} durationMs={}",
                        traceId(),
                        provider,
                        stage(),
                        classification.failureCategory(),
                        classification.exceptionClass(),
                        classification.rootExceptionClass(),
                        classification.rootSourceClass(),
                        classification.rootSourceMethod(),
                        classification.sqlState(),
                        elapsedMillis(startedAtNanos));
                throw failure;
            }
        }
    }

    private static String provider(ProceedingJoinPoint joinPoint) {
        Object[] arguments = joinPoint.getArgs();
        if (arguments == null) {
            return UNAVAILABLE;
        }
        for (Object argument : arguments) {
            if (argument instanceof TrustedOAuthIdentity identity
                    && identity.provider() != null) {
                return identity.provider().name();
            }
        }
        return UNAVAILABLE;
    }

    private static String stage() {
        OAuthAccountResolutionDiagnosticContext.Stage value =
                OAuthAccountResolutionDiagnosticContext.currentStage();
        return value == null ? UNAVAILABLE : value.name();
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value != null && SAFE_TRACE_ID.matcher(value).matches()
                ? value : UNAVAILABLE;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedAtNanos));
    }
}
