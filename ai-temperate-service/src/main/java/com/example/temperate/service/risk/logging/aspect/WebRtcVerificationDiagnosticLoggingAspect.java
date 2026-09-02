package com.example.temperate.service.risk.logging.aspect;

import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationOutcome;
import java.util.List;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 该切面是来关联 WebRTC 浏览器尝试、业务判定与 Redis 原子迁移，并只输出枚举、计数和耗时等低敏证据。
 *
 * <p>切面不会格式化 HTTP IP、候选地址、Token 摘要、设备摘要、密文或方法完整参数；Store 切点只读取固定位置的
 * 状态结果，Service 切点只读取候选数量与公开判定，避免诊断日志成为网络身份数据的旁路。</p>
 */
@Aspect
@Component
public final class WebRtcVerificationDiagnosticLoggingAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(WebRtcVerificationDiagnosticLoggingAspect.class);
    private static final String ABSENT = "absent";
    private static final long DEFAULT_REPORT_GRACE_MILLIS = 3_000L;
    private static final Pattern SAFE_VALUE =
            Pattern.compile("^[A-Za-z0-9._:/{}-]{1,255}$");
    // Service report 与 Store 写入保持同一同步线程；finally 恢复嵌套值，既关联 writeResult 又避免线程池串号。
    private static final ThreadLocal<Boolean> REPORT_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<PreAuthWebRtcWriteResult> REPORT_WRITE_RESULT =
            new ThreadLocal<>();

    private final long reportGraceMillis;

    @Autowired
    public WebRtcVerificationDiagnosticLoggingAspect(NetworkRiskProperties properties) {
        this.reportGraceMillis = Math.max(
                1L,
                properties.webRtc().reportGrace().toMillis());
    }

    WebRtcVerificationDiagnosticLoggingAspect() {
        this.reportGraceMillis = DEFAULT_REPORT_GRACE_MILLIS;
    }

    /**
     * 记录 start 对外业务结论；精确的 Redis STARTED/PENDING_PRESERVED 由内层 Store 切点记录。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.webrtc.service."
                    + "WebRtcVerificationService.begin(..))")
    public Object logBegin(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        try {
            Object result = joinPoint.proceed();
            WebRtcVerificationDecision decision = result instanceof WebRtcVerificationDecision value
                    ? value
                    : null;
            logDecision(
                    "webrtc_begin_completed",
                    access,
                    decision,
                    0,
                    startedAtNanos,
                    null,
                    ABSENT,
                    decision == null || terminal(decision.outcome()));
            return result;
        } catch (Throwable failure) {
            logDecision(
                    "webrtc_begin_completed",
                    access,
                    null,
                    0,
                    startedAtNanos,
                    failure,
                    ABSENT,
                    true);
            throw failure;
        }
    }

    /**
     * 记录 Redis begin Lua 的原子结果，使重复 start 能与首次开启窗口明确区分。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.store."
                    + "PreAuthStore.beginWebRtcVerification(..))")
    public Object logBeginStore(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        RiskScope scope = argument(joinPoint, 0, RiskScope.class);
        try {
            Object result = joinPoint.proceed();
            PreAuthWebRtcBeginResult begin = result instanceof PreAuthWebRtcBeginResult value
                    ? value
                    : null;
            boolean warn = begin == null
                    || begin.status() == PreAuthWebRtcBeginResult.Status.FAILURE_PRESERVED
                    || begin.status() == PreAuthWebRtcBeginResult.Status.START_TIMEOUT
                    || begin.status() == PreAuthWebRtcBeginResult.Status.REPORT_TIMEOUT
                    || begin.status() == PreAuthWebRtcBeginResult.Status.NETWORK_CHANGED
                    || begin.status() == PreAuthWebRtcBeginResult.Status.STATE_INVALID
                    || (begin.status() == PreAuthWebRtcBeginResult.Status.PENDING_PRESERVED
                    && begin.remainingMillis() < reportGraceMillis);
            String template = correlationTemplate("webrtc_begin_store_completed")
                    + " scope={} result={} generation={} remainingMs={} exceptionClass={} durationMs={}";
            Object[] arguments = appendCorrelation(
                    safeEnum(scope),
                    begin == null ? ABSENT : begin.status().name(),
                    begin == null ? 0L : begin.generation(),
                    begin == null ? 0L : begin.remainingMillis(),
                    ABSENT,
                    elapsedMillis(startedAtNanos));
            log(template, arguments, warn, false);
            return result;
        } catch (Throwable failure) {
            String template = correlationTemplate("webrtc_begin_store_completed")
                    + " scope={} result={} generation={} remainingMs={} exceptionClass={} durationMs={}";
            log(
                    template,
                    appendCorrelation(
                            safeEnum(scope),
                            "FAILED",
                            0L,
                            0L,
                            exceptionClass(failure),
                            elapsedMillis(startedAtNanos)),
                    true,
                    false);
            throw failure;
        }
    }

    /**
     * 记录 report 的候选数量和业务终态，禁止读取或输出候选集合元素。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.webrtc.service."
                    + "WebRtcVerificationService.report(..))")
    public Object logReport(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        // report 存在普通四参数与 OAuth 五参数重载，候选集合始终是最后一个参数。
        int submittedCandidateCount = boundedCollectionSize(
                joinPoint,
                joinPoint.getArgs().length - 1);
        Boolean previousReportScope = REPORT_SCOPE.get();
        PreAuthWebRtcWriteResult previousWriteResult = REPORT_WRITE_RESULT.get();
        REPORT_SCOPE.set(Boolean.TRUE);
        REPORT_WRITE_RESULT.remove();
        String receivedTemplate = correlationTemplate("webrtc_report_received")
                + " scope={} submittedCandidateCount={}";
        log(
                receivedTemplate,
                appendCorrelation(safeScope(access), submittedCandidateCount),
                submittedCandidateCount == 0,
                false);
        try {
            Object result = joinPoint.proceed();
            WebRtcVerificationDecision decision = result instanceof WebRtcVerificationDecision value
                    ? value
                    : null;
            logDecision(
                    "webrtc_report_completed",
                    access,
                    decision,
                    submittedCandidateCount,
                    startedAtNanos,
                    null,
                    safeEnum(REPORT_WRITE_RESULT.get()),
                    decision == null || decision.outcome() != WebRtcVerificationOutcome.VERIFIED);
            return result;
        } catch (Throwable failure) {
            logDecision(
                    "webrtc_report_completed",
                    access,
                    null,
                    submittedCandidateCount,
                    startedAtNanos,
                    failure,
                    safeEnum(REPORT_WRITE_RESULT.get()),
                    true);
            throw failure;
        } finally {
            restoreThreadLocal(REPORT_SCOPE, previousReportScope);
            restoreThreadLocal(REPORT_WRITE_RESULT, previousWriteResult);
        }
    }

    /**
     * 记录 report 写入 Redis 后的并发裁决，只读取枚举、generation 与证据是否存在。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.preauth.store."
                    + "PreAuthStore.writeWebRtcResult(..))")
    public Object logResultStore(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        RiskScope scope = argument(joinPoint, 0, RiskScope.class);
        long generation = longArgument(joinPoint, 4);
        boolean matched = booleanArgument(joinPoint, 5);
        PreAuthWebRtcFailureReason reason = argument(
                joinPoint,
                6,
                PreAuthWebRtcFailureReason.class);
        boolean evidencePresent = booleanArgument(joinPoint, 8);
        try {
            Object result = joinPoint.proceed();
            PreAuthWebRtcWriteResult writeResult = result instanceof PreAuthWebRtcWriteResult value
                    ? value
                    : null;
            if (Boolean.TRUE.equals(REPORT_SCOPE.get()) && writeResult != null) {
                REPORT_WRITE_RESULT.set(writeResult);
            }
            String template = correlationTemplate("webrtc_result_store_completed")
                    + " scope={} generation={} matched={} failureReason={} evidencePresent={} "
                    + "writeResult={} exceptionClass={} durationMs={}";
            Object[] arguments = appendCorrelation(
                    safeEnum(scope),
                    generation,
                    matched,
                    safeEnum(reason),
                    evidencePresent,
                    safeEnum(writeResult),
                    ABSENT,
                    elapsedMillis(startedAtNanos));
            log(
                    template,
                    arguments,
                    writeResult != PreAuthWebRtcWriteResult.UPDATED || !matched,
                    false);
            return result;
        } catch (Throwable failure) {
            String template = correlationTemplate("webrtc_result_store_completed")
                    + " scope={} generation={} matched={} failureReason={} evidencePresent={} "
                    + "writeResult={} exceptionClass={} durationMs={}";
            log(
                    template,
                    appendCorrelation(
                            safeEnum(scope),
                            generation,
                            matched,
                            safeEnum(reason),
                            evidencePresent,
                            "FAILED",
                            exceptionClass(failure),
                            elapsedMillis(startedAtNanos)),
                    true,
                    false);
            throw failure;
        }
    }

    /**
     * 正常 inspect 仅在 DEBUG 输出；阻断或状态异常提升为 WARN，避免生产请求逐条产生 INFO 噪声。
     */
    @Around(
            "execution(* com.example.temperate.service.risk.webrtc.service."
                    + "WebRtcVerificationService.inspect(..))")
    public Object logInspect(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAtNanos = System.nanoTime();
        PreAuthAccess access = argument(joinPoint, 0, PreAuthAccess.class);
        try {
            Object result = joinPoint.proceed();
            WebRtcVerificationDecision decision = result instanceof WebRtcVerificationDecision value
                    ? value
                    : null;
            logDecision(
                    "webrtc_inspect_completed",
                    access,
                    decision,
                    0,
                    startedAtNanos,
                    null,
                    ABSENT,
                    decision == null || terminal(decision.outcome()));
            return result;
        } catch (Throwable failure) {
            logDecision(
                    "webrtc_inspect_completed",
                    access,
                    null,
                    0,
                    startedAtNanos,
                    failure,
                    ABSENT,
                    true);
            throw failure;
        }
    }

    private static void logDecision(
            String event,
            PreAuthAccess access,
            WebRtcVerificationDecision decision,
            int submittedCandidateCount,
            long startedAtNanos,
            Throwable failure,
            String writeResult,
            boolean warn) {
        String template = correlationTemplate(event)
                + " scope={} decision={} verificationState={} generation={} remainingMs={} "
                + "failureReason={} submittedCandidateCount={} webRtcStatus={} retryable={} writeResult={} "
                + "exceptionClass={} durationMs={}";
        Object[] arguments = appendCorrelation(
                safeScope(access),
                decision == null ? ABSENT : decision.outcome().name(),
                decision == null ? ABSENT : safeValue(decision.verificationState()),
                decision == null ? 0L : decision.probeGeneration(),
                decision == null ? 0L : decision.pendingRemainingMillis(),
                decision == null ? ABSENT : safeEnum(decision.failureReason()),
                submittedCandidateCount,
                decision == null || decision.webRtcStatus() == null
                        ? ABSENT
                        : decision.webRtcStatus(),
                decision != null && retryable(decision.outcome()),
                writeResult,
                exceptionClass(failure),
                elapsedMillis(startedAtNanos));
        log(template, arguments, warn, "webrtc_inspect_completed".equals(event));
    }

    private static String correlationTemplate(String event) {
        return "event=" + event
                + " traceId={} clientRequestId={} pageInstanceId={} probeRunId={} "
                + "path={} platform={}";
    }

    private static Object[] appendCorrelation(Object... eventArguments) {
        Object[] correlation = {
            safeMdc("traceId"),
            safeMdc("clientRequestId"),
            safeMdc("pageInstanceId"),
            safeMdc("webRtcProbeRunId"),
            safeMdc("authRequestPath"),
            safeMdc("authClientPlatform")
        };
        Object[] combined = new Object[correlation.length + eventArguments.length];
        System.arraycopy(correlation, 0, combined, 0, correlation.length);
        System.arraycopy(
                eventArguments,
                0,
                combined,
                correlation.length,
                eventArguments.length);
        return combined;
    }

    private static void log(
            String template,
            Object[] arguments,
            boolean warn,
            boolean debugWhenNormal) {
        if (warn) {
            LOGGER.warn(template, arguments);
        } else if (debugWhenNormal) {
            LOGGER.debug(template, arguments);
        } else {
            LOGGER.info(template, arguments);
        }
    }

    private static boolean terminal(WebRtcVerificationOutcome outcome) {
        return outcome != null && switch (outcome) {
            case VERIFIED, VERIFICATION_REQUIRED, VERIFICATION_PENDING -> false;
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT, IP_FAMILY_INCOMPLETE,
                    IP_MISMATCH, NETWORK_CHANGED, OAUTH_ATTEMPT_REQUIRED,
                    STALE_REPORT, STATE_INVALID -> true;
        };
    }

    private static boolean retryable(WebRtcVerificationOutcome outcome) {
        return outcome == WebRtcVerificationOutcome.NETWORK_CHANGED
                || outcome == WebRtcVerificationOutcome.STALE_REPORT;
    }

    private static int boundedCollectionSize(ProceedingJoinPoint joinPoint, int index) {
        Object[] arguments = joinPoint.getArgs();
        if (arguments == null || index < 0 || index >= arguments.length) {
            return 0;
        }
        Object value = arguments[index];
        return value instanceof List<?> list ? Math.min(8, list.size()) : 0;
    }

    private static long longArgument(ProceedingJoinPoint joinPoint, int index) {
        Object value = rawArgument(joinPoint, index);
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static boolean booleanArgument(ProceedingJoinPoint joinPoint, int index) {
        return Boolean.TRUE.equals(rawArgument(joinPoint, index));
    }

    private static Object rawArgument(ProceedingJoinPoint joinPoint, int index) {
        Object[] arguments = joinPoint.getArgs();
        return arguments == null || index < 0 || index >= arguments.length
                ? null
                : arguments[index];
    }

    private static <T> T argument(
            ProceedingJoinPoint joinPoint,
            int index,
            Class<T> expectedType) {
        Object value = rawArgument(joinPoint, index);
        return expectedType.isInstance(value) ? expectedType.cast(value) : null;
    }

    private static String safeScope(PreAuthAccess access) {
        return access == null || access.state() == null
                ? ABSENT
                : safeEnum(access.state().scope());
    }

    private static String safeEnum(Enum<?> value) {
        return value == null ? ABSENT : safeValue(value.name());
    }

    private static String safeMdc(String key) {
        return safeValue(MDC.get(key));
    }

    private static String exceptionClass(Throwable failure) {
        return failure == null ? ABSENT : safeValue(failure.getClass().getSimpleName());
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }
        String normalized = value.trim();
        return SAFE_VALUE.matcher(normalized).matches()
                ? normalized
                : ABSENT;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static <T> void restoreThreadLocal(ThreadLocal<T> holder, T previous) {
        if (previous == null) {
            holder.remove();
        } else {
            holder.set(previous);
        }
    }
}
