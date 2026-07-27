package com.example.temperate.service.humanverification.logging.aspect;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;

/**
 * 记录管理员 hCaptcha 服务的订阅边界、最终结果和耗时，用于把供应商及本地判定日志关联到同一次调用。
 *
 * <p>切面只读取策略类型、实现类名、Reactor Context 和受控异常类型，绝不读取方法参数、异常消息或
 * cause 内容；原始 Token、IP、Challenge 和 Cookie 因而不会进入该调用边界日志。</p>
 */
@Aspect
@Component
public final class HcaptchaVerificationLoggingAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HcaptchaVerificationLoggingAspect.class);
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SAFE_DIAGNOSTIC_VALUE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /**
     * 在冷 Mono 被实际订阅后才计时和记录，保证不改变 WebClient 的惰性、重试及异常传播语义。
     */
    @Around(
            "execution(reactor.core.publisher.Mono "
                    + "com.example.temperate.service.humanverification."
                    + "HumanVerificationService.verify(..))")
    public Object logVerificationBoundary(ProceedingJoinPoint joinPoint) throws Throwable {
        Object target = joinPoint.getTarget();
        if (!(target instanceof HumanVerificationService service)
                || service.type() != HumanVerificationType.HCAPTCHA) {
            return joinPoint.proceed();
        }

        Object result = joinPoint.proceed();
        if (!(result instanceof Mono<?> operation)) {
            return result;
        }

        String implementation = sanitizeDiagnosticValue(
                ClassUtils.getUserClass(target).getSimpleName());
        @SuppressWarnings("unchecked")
        Mono<Void> verification = (Mono<Void>) operation;
        return Mono.deferContextual(contextView -> {
            String traceId = sanitizeTraceId(contextView.getOrDefault(
                    HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                    "absent"));
            long startedAtNanos = System.nanoTime();
            AtomicBoolean completionLogged = new AtomicBoolean();
            LOGGER.info(
                    "event=admin_hcaptcha_verification_started traceId={} implementation={}",
                    traceId,
                    implementation);
            return verification
                    .doOnSuccess(ignored -> logCompleted(
                            completionLogged,
                            traceId,
                            "succeeded",
                            UNAVAILABLE,
                            UNAVAILABLE,
                            startedAtNanos,
                            false))
                    .doOnError(failure -> {
                        CompletionClassification classification =
                                classify(failure);
                        logCompleted(
                                completionLogged,
                                traceId,
                                classification.outcome(),
                                classification.adminCode(),
                                classification.exceptionClass(),
                                startedAtNanos,
                                true);
                    })
                    .doOnCancel(() -> logCompleted(
                            completionLogged,
                            traceId,
                            "cancelled",
                            UNAVAILABLE,
                            UNAVAILABLE,
                            startedAtNanos,
                            true));
        });
    }

    private static CompletionClassification classify(Throwable failure) {
        if (failure instanceof AdminException adminFailure) {
            AdminErrorCode code = adminFailure.code();
            String outcome = switch (code) {
                case HCAPTCHA_REJECTED -> "rejected";
                case HCAPTCHA_UNAVAILABLE -> "unavailable";
                default -> "failed";
            };
            return new CompletionClassification(
                    outcome,
                    code.name(),
                    AdminException.class.getSimpleName());
        }
        return new CompletionClassification(
                "failed",
                UNAVAILABLE,
                failure == null
                        ? UNAVAILABLE
                        : sanitizeDiagnosticValue(failure.getClass().getSimpleName()));
    }

    private static void logCompleted(
            AtomicBoolean completionLogged,
            String traceId,
            String outcome,
            String adminCode,
            String exceptionClass,
            long startedAtNanos,
            boolean warn) {
        // 成功、错误和取消信号可能在竞争边界相邻到达；原子门禁保证每次订阅只输出一条完成日志。
        if (!completionLogged.compareAndSet(false, true)) {
            return;
        }
        long durationMs = elapsedMillis(startedAtNanos);
        if (warn) {
            LOGGER.warn(
                    "event=admin_hcaptcha_verification_completed traceId={} outcome={} "
                            + "adminCode={} exceptionClass={} durationMs={}",
                    traceId,
                    outcome,
                    adminCode,
                    exceptionClass,
                    durationMs);
            return;
        }
        LOGGER.info(
                "event=admin_hcaptcha_verification_completed traceId={} outcome={} "
                        + "adminCode={} exceptionClass={} durationMs={}",
                traceId,
                outcome,
                adminCode,
                exceptionClass,
                durationMs);
    }

    private static String sanitizeTraceId(String value) {
        String sanitized = sanitizeDiagnosticValue(value);
        return UNAVAILABLE.equals(sanitized) ? "absent" : sanitized;
    }

    private static String sanitizeDiagnosticValue(String value) {
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

    private record CompletionClassification(
            String outcome,
            String adminCode,
            String exceptionClass) {
    }
}
