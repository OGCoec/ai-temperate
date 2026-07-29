package com.example.temperate.service.admin.mailinspection.diagnostic;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 为显式标记的邮件检查操作记录模板化耗时和失败类别，不记录参数、返回值或真实 Job ID。
 */
@Aspect
@Component
public final class MailInspectionDiagnosticAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MailInspectionDiagnosticAspect.class);

    private final MailInspectionFailureClassifier failureClassifier;

    public MailInspectionDiagnosticAspect(
            MailInspectionFailureClassifier failureClassifier) {
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    /**
     * 保留调用方 MDC 中的 traceId，并以固定 operation 字段记录完成结果。
     */
    @Around(
            "@annotation(operation)")
    public Object observe(
            ProceedingJoinPoint joinPoint,
            MailInspectionDiagnosticOperation operation) throws Throwable {
        long started = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            LOGGER.info(
                    "event={} operation={} outcome={} durationMs={}",
                    "admin_mail_inspection_operation",
                    operation.value(),
                    "success",
                    elapsedMillis(started));
            return result;
        } catch (Throwable failure) {
            LOGGER.warn(
                    "event={} operation={} outcome={} failureCategory={} "
                            + "exceptionType={} durationMs={}",
                    "admin_mail_inspection_operation",
                    operation.value(),
                    "failure",
                    failureClassifier.classify(failure),
                    failure.getClass().getName(),
                    elapsedMillis(started));
            throw failure;
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - started));
    }
}
