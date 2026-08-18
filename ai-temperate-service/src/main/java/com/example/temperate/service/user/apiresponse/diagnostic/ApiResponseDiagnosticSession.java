package com.example.temperate.service.user.apiresponse.diagnostic;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.SignalType;

/**
 * 该会话是来以线程安全、有界且不保存业务正文的方式汇总单条 Responses 请求的流边界、背压 demand 和终止证据。
 * 会话只保存固定枚举、计数、字节数、序号与异常类型；input、output、工具参数、reasoning、凭据和异常消息均不得进入日志。
 */
public final class ApiResponseDiagnosticSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiResponseDiagnosticSession.class);
    private static final String DIAGNOSTIC_SCHEMA = "responses-diag-v1";
    private static final ApiResponseDiagnosticSession DISABLED =
            new ApiResponseDiagnosticSession();

    private final boolean enabled;
    private final boolean sampled;
    private final String traceId;
    private final String mode;
    private final ApiKeyProperties.StreamDiagnostics properties;
    private final ApiResponseDiagnosticClock clock;
    private final long startedNanos;
    private final Map<ApiResponseDiagnosticBoundary, Long> boundaryCounts =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> boundaryBytes =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> lastFrameNanos =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> windowStartedNanos =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> windowFrames =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> windowBytes =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Long> burstStartedNanos =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Map<ApiResponseDiagnosticBoundary, Integer> burstFrames =
            new EnumMap<>(ApiResponseDiagnosticBoundary.class);
    private final Deque<String> terminalHistory = new ArrayDeque<>();
    private final AtomicBoolean summarized = new AtomicBoolean();

    private ApiResponseDiagnosticStage observedStage;
    private boolean subscribed;
    private boolean bodySubscribed;
    private boolean responsePrepared;
    private boolean terminalSeen;
    private boolean usagePresent;
    private boolean clientCancelled;
    private boolean upstreamCancelled;
    private long lastSequence = -1L;
    private long downstreamRequestCalls;
    private long downstreamRequested;
    private long upstreamRequestCalls;
    private long upstreamRequested;
    private long bodyEmitAttempts;
    private long bodyEmitSucceeded;
    private ApiResponseFailureStage failureStage;
    private String failureType = "none";
    private String rootFailureType = "none";

    private ApiResponseDiagnosticSession() {
        enabled = false;
        sampled = false;
        traceId = "disabled";
        mode = "unknown";
        properties = null;
        clock = System::nanoTime;
        startedNanos = 0L;
    }

    public ApiResponseDiagnosticSession(
            ApiKeyProperties.StreamDiagnostics properties,
            ApiResponseDiagnosticClock clock,
            boolean sampled,
            String mode) {
        this.enabled = true;
        this.sampled = sampled;
        this.properties = properties;
        this.clock = clock;
        this.mode = safeMode(mode);
        String requestTrace = MDC.get("apiChatTraceId");
        this.traceId = safeTraceId(requestTrace == null || requestTrace.isBlank()
                ? UUID.randomUUID().toString() : requestTrace);
        this.startedNanos = clock.nanoTime();
        for (ApiResponseDiagnosticBoundary boundary
                : ApiResponseDiagnosticBoundary.values()) {
            boundaryCounts.put(boundary, 0L);
            boundaryBytes.put(boundary, 0L);
            lastFrameNanos.put(boundary, startedNanos);
            windowStartedNanos.put(boundary, startedNanos);
            windowFrames.put(boundary, 0L);
            windowBytes.put(boundary, 0L);
            burstStartedNanos.put(boundary, startedNanos);
            burstFrames.put(boundary, 0);
        }
    }

    public static ApiResponseDiagnosticSession disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean sampled() {
        return sampled;
    }

    public String traceId() {
        return traceId;
    }

    public String mode() {
        return mode;
    }

    public synchronized void recordStageEnter(ApiResponseDiagnosticStage stage) {
        if (!enabled) {
            return;
        }
        if (observedStage == null) {
            observedStage = stage;
        }
        if (sampled) {
            safeInfo(
                    "event=api_responses_stage_enter diagnosticSchema={} traceId={} protocol=responses mode={} stage={}",
                    DIAGNOSTIC_SCHEMA, traceId, mode, stage);
        }
    }

    public synchronized void recordStageReturn(
            ApiResponseDiagnosticStage stage,
            long elapsedNanos,
            Object result) {
        if (enabled && sampled) {
            safeInfo(
                    "event=api_responses_stage_return diagnosticSchema={} traceId={} protocol=responses mode={} stage={} elapsedMs={} returnType={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    stage,
                    millis(elapsedNanos),
                    safeType(result == null ? null : result.getClass()));
        }
    }

    public synchronized void recordSubscribed() {
        if (!enabled || subscribed) {
            return;
        }
        subscribed = true;
        remember("PUBLISHER_SUBSCRIBED");
    }

    public synchronized void recordBodySubscribed() {
        if (!enabled || bodySubscribed) {
            return;
        }
        bodySubscribed = true;
        remember("BODY_SUBSCRIBED");
        if (sampled) {
            gateLog("body_subscribed", -1L, -1L);
        }
    }

    public synchronized void recordResponsePrepared() {
        if (!enabled || responsePrepared) {
            return;
        }
        responsePrepared = true;
        remember("RESPONSE_PREPARED");
        if (sampled) {
            gateLog("response_prepared", -1L, -1L);
        }
    }

    public synchronized void recordBoundary(
            ApiResponseDiagnosticBoundary boundary,
            long bytes,
            ApiResponseFrameClass frameClass,
            long sequence,
            TerminalKind terminalKind,
            boolean frameUsagePresent) {
        if (!enabled) {
            return;
        }
        long now = clock.nanoTime();
        long safeBytes = Math.max(0L, bytes);
        long count = saturatedAdd(boundaryCounts.get(boundary), 1L);
        boundaryCounts.put(boundary, count);
        boundaryBytes.put(boundary, saturatedAdd(boundaryBytes.get(boundary), safeBytes));
        long gapNanos = Math.max(0L, now - lastFrameNanos.get(boundary));
        lastFrameNanos.put(boundary, now);
        if (sequence >= 0L) {
            lastSequence = sequence;
        }
        if (terminalKind != null
                && terminalKind != TerminalKind.NONE
                && terminalKind != TerminalKind.LEGACY_DONE) {
            terminalSeen = true;
        }
        usagePresent |= frameUsagePresent;
        remember(boundary + ":" + safeFrameClass(frameClass) + ":seq=" + sequence);
        recordWindow(boundary, now, safeBytes);
        recordBurst(boundary, now);
        if (sampled && (count == 1L
                || count % properties.getLogEveryFrames() == 0L
                || terminalKind != null && terminalKind != TerminalKind.NONE)) {
            safeInfo(
                    "event=api_responses_gate_transition diagnosticSchema={} traceId={} protocol=responses mode={} boundary={} frameClass={} frame={} bytes={} sequence={} terminalKind={} usagePresent={} gapMs={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    boundary,
                    safeFrameClass(frameClass),
                    count,
                    safeBytes,
                    sequence,
                    terminalKind == null ? "NONE" : terminalKind,
                    frameUsagePresent,
                    millis(gapNanos));
        }
    }

    public synchronized void recordDownstreamRequest(long requested) {
        if (!enabled) {
            return;
        }
        downstreamRequestCalls = saturatedAdd(downstreamRequestCalls, 1L);
        downstreamRequested = saturatedAdd(downstreamRequested, positiveDemand(requested));
        remember("DOWNSTREAM_REQUEST:n=" + requested);
        demandLog("downstream", requested, downstreamRequestCalls, downstreamRequested);
    }

    public synchronized void recordUpstreamRequest(long requested) {
        if (!enabled) {
            return;
        }
        upstreamRequestCalls = saturatedAdd(upstreamRequestCalls, 1L);
        upstreamRequested = saturatedAdd(upstreamRequested, positiveDemand(requested));
        remember("UPSTREAM_REQUEST:n=" + requested);
        demandLog("upstream", requested, upstreamRequestCalls, upstreamRequested);
    }

    public synchronized void recordEmitAttempt(long downstreamDemand, long sequence) {
        if (!enabled) {
            return;
        }
        bodyEmitAttempts = saturatedAdd(bodyEmitAttempts, 1L);
        remember("MVC_BODY_EMIT_ATTEMPT:demand="
                + Math.max(0L, downstreamDemand) + ":seq=" + sequence);
        if (sampled) {
            gateLog("emit_attempt", Math.max(0L, downstreamDemand), sequence);
        }
    }

    public synchronized void recordEmitSucceeded(
            long bytes,
            ApiResponseFrameClass frameClass,
            long sequence,
            TerminalKind terminalKind,
            boolean frameUsagePresent) {
        if (!enabled) {
            return;
        }
        bodyEmitSucceeded = saturatedAdd(bodyEmitSucceeded, 1L);
        recordBoundary(
                ApiResponseDiagnosticBoundary.MVC_BODY_EMIT,
                bytes,
                frameClass,
                sequence,
                terminalKind,
                frameUsagePresent);
        if (sampled) {
            gateLog("emit_succeeded", -1L, sequence);
        }
    }

    public synchronized void recordClientCancelled() {
        if (!enabled) {
            return;
        }
        clientCancelled = true;
        remember("CLIENT_CANCELLED");
    }

    /** 记录 Controller 已经反向取消唯一上游订阅，用于区分客户端取消与本地写出故障。 */
    public synchronized void recordUpstreamCancelled() {
        if (!enabled) {
            return;
        }
        upstreamCancelled = true;
        remember("UPSTREAM_CANCELLED");
    }

    public synchronized void recordTerminalSignal(String signal) {
        if (enabled) {
            remember("TERMINAL_SIGNAL:" + safeSignal(signal));
        }
    }

    /**
     * 失败日志只保留异常类、根因类和有界 class#method 栈；异常 message 可能携带上游正文，任何级别都禁止输出。
     */
    public synchronized void recordFailure(
            ApiResponseFailureStage stage,
            Throwable failure) {
        if (!enabled || failure == null) {
            return;
        }
        failureStage = stage == null ? ApiResponseFailureStage.UNKNOWN : stage;
        failureType = safeType(failure.getClass());
        rootFailureType = rootFailureType(failure);
        remember("FAILURE:" + failureStage + ":" + failureType);
        Object[] arguments = {
            DIAGNOSTIC_SCHEMA,
            traceId,
            mode,
            failureStage,
            failureType,
            rootFailureType,
            sampled,
            elapsedMillis(),
            safeStack(failure)
        };
        String template = "event=api_responses_stream_failure diagnosticSchema={} traceId={} protocol=responses mode={} failureStage={} failureType={} rootFailureType={} sampled={} elapsedMs={} stack={}";
        if (failureStage == ApiResponseFailureStage.MVC_BODY
                || failureType.contains("OverflowException")) {
            safeError(template, arguments);
        } else {
            safeWarn(template, arguments);
        }
    }

    public void summarize(SignalType signal, boolean responseCommitted) {
        if (!enabled || !summarized.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            boolean failed = signal == SignalType.ON_ERROR || failureStage != null;
            boolean cancelledSignal = signal == SignalType.CANCEL;
            if (!sampled && !failed && !cancelledSignal) {
                return;
            }
            safeInfo(
                    "event=api_responses_stream_summary diagnosticSchema={} traceId={} protocol=responses mode={} stage={} signal={} elapsedMs={} rawFrames={} rawBytes={} parsedFrames={} parsedBytes={} businessFrames={} businessBytes={} gateFrames={} gateBytes={} bodyFrames={} bodyBytes={} bodyEmitAttempts={} bodyEmitSucceeded={} downstreamRequestCalls={} downstreamRequested={} upstreamRequestCalls={} upstreamRequested={} lastSequence={} terminalSeen={} usagePresent={} bodySubscribed={} responsePrepared={} clientCancelled={} upstreamCancelled={} failureStage={} failureType={} rootFailureType={} responseCommitted={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    observedStage == null ? "unknown" : observedStage,
                    signal == null ? "UNKNOWN" : signal,
                    elapsedMillis(),
                    count(ApiResponseDiagnosticBoundary.UPSTREAM_RAW),
                    bytes(ApiResponseDiagnosticBoundary.UPSTREAM_RAW),
                    count(ApiResponseDiagnosticBoundary.AFTER_PROTOCOL_PARSE),
                    bytes(ApiResponseDiagnosticBoundary.AFTER_PROTOCOL_PARSE),
                    count(ApiResponseDiagnosticBoundary.AFTER_BUSINESS_GATE),
                    bytes(ApiResponseDiagnosticBoundary.AFTER_BUSINESS_GATE),
                    count(ApiResponseDiagnosticBoundary.CONTROLLER_GATE_RECEIVED),
                    bytes(ApiResponseDiagnosticBoundary.CONTROLLER_GATE_RECEIVED),
                    count(ApiResponseDiagnosticBoundary.MVC_BODY_EMIT),
                    bytes(ApiResponseDiagnosticBoundary.MVC_BODY_EMIT),
                    bodyEmitAttempts,
                    bodyEmitSucceeded,
                    downstreamRequestCalls,
                    downstreamRequested,
                    upstreamRequestCalls,
                    upstreamRequested,
                    lastSequence,
                    terminalSeen,
                    usagePresent,
                    bodySubscribed,
                    responsePrepared,
                    clientCancelled,
                    upstreamCancelled,
                    failureStage == null ? "none" : failureStage,
                    failureType,
                    rootFailureType,
                    responseCommitted);
            // 取消与失败同样需要有界历史，才能确认客户端离开后是否真正停止了 8317 上游。
            if (failed || cancelledSignal) {
                logTerminalHistoryParts();
            }
        }
    }

    private void recordWindow(
            ApiResponseDiagnosticBoundary boundary,
            long now,
            long bytes) {
        long windowStarted = windowStartedNanos.get(boundary);
        if (now - windowStarted >= properties.getWindow().toNanos()) {
            if (sampled) {
                safeInfo(
                        "event=api_responses_stream_window diagnosticSchema={} traceId={} protocol=responses mode={} boundary={} elapsedMs={} frames={} bytes={}",
                        DIAGNOSTIC_SCHEMA,
                        traceId,
                        mode,
                        boundary,
                        millis(now - windowStarted),
                        windowFrames.get(boundary),
                        windowBytes.get(boundary));
            }
            windowStartedNanos.put(boundary, now);
            windowFrames.put(boundary, 0L);
            windowBytes.put(boundary, 0L);
        }
        windowFrames.put(boundary, saturatedAdd(windowFrames.get(boundary), 1L));
        windowBytes.put(boundary, saturatedAdd(windowBytes.get(boundary), bytes));
    }

    private void recordBurst(ApiResponseDiagnosticBoundary boundary, long now) {
        long started = burstStartedNanos.get(boundary);
        int frames = burstFrames.get(boundary);
        if (now - started > properties.getBurstWindow().toNanos()) {
            burstStartedNanos.put(boundary, now);
            frames = 0;
        }
        frames++;
        burstFrames.put(boundary, frames);
        if (sampled && frames == properties.getBurstFrames()) {
            safeInfo(
                    "event=api_responses_stream_window diagnosticSchema={} traceId={} protocol=responses mode={} boundary={} result=burst frames={} windowMs={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    boundary,
                    frames,
                    properties.getBurstWindow().toMillis());
        }
    }

    private void demandLog(
            String direction,
            long requested,
            long calls,
            long total) {
        if (sampled) {
            safeInfo(
                    "event=api_responses_demand diagnosticSchema={} traceId={} protocol=responses mode={} direction={} requested={} calls={} totalRequested={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    direction,
                    requested,
                    calls,
                    total);
        }
    }

    private void gateLog(String action, long demand, long sequence) {
        safeInfo(
                "event=api_responses_gate_transition diagnosticSchema={} traceId={} protocol=responses mode={} action={} downstreamDemand={} sequence={}",
                DIAGNOSTIC_SCHEMA,
                traceId,
                mode,
                action,
                demand,
                sequence);
    }

    /** 失败历史每行最多八项，避免日志平台截断真正发生错误前的 demand 和最后边界。 */
    private void logTerminalHistoryParts() {
        if (terminalHistory.isEmpty()) {
            return;
        }
        List<String> history = List.copyOf(terminalHistory);
        int entriesPerPart = 8;
        int parts = (history.size() + entriesPerPart - 1) / entriesPerPart;
        for (int part = 0; part < parts; part++) {
            int from = part * entriesPerPart;
            int to = Math.min(history.size(), from + entriesPerPart);
            safeWarn(
                    "event=api_responses_stream_terminal_history diagnosticSchema={} traceId={} protocol=responses mode={} part={} parts={} entries={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    mode,
                    part + 1,
                    parts,
                    String.join(",", history.subList(from, to)));
        }
    }

    private long count(ApiResponseDiagnosticBoundary boundary) {
        return boundaryCounts.getOrDefault(boundary, 0L);
    }

    private long bytes(ApiResponseDiagnosticBoundary boundary) {
        return boundaryBytes.getOrDefault(boundary, 0L);
    }

    private void remember(String metadata) {
        while (terminalHistory.size() >= properties.getTerminalHistorySize()) {
            terminalHistory.removeFirst();
        }
        terminalHistory.addLast(metadata);
    }

    private long elapsedMillis() {
        return millis(Math.max(0L, clock.nanoTime() - startedNanos));
    }

    private static long positiveDemand(long requested) {
        return requested <= 0L ? 0L : requested;
    }

    private static long saturatedAdd(long current, long increment) {
        long safeIncrement = Math.max(0L, increment);
        return Long.MAX_VALUE - current < safeIncrement
                ? Long.MAX_VALUE : current + safeIncrement;
    }

    private static long millis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toMillis();
    }

    private static String safeFrameClass(ApiResponseFrameClass frameClass) {
        return frameClass == null ? ApiResponseFrameClass.UNKNOWN.name() : frameClass.name();
    }

    private static String safeMode(String value) {
        return "sse".equals(value) || "json".equals(value) ? value : "unknown";
    }

    private static String safeSignal(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{1,64}")
                ? value : "UNKNOWN";
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static String safeType(Class<?> type) {
        String name = type == null ? "null" : type.getName();
        return name.matches("[A-Za-z0-9_.$]{1,200}") ? name : "unknown";
    }

    private static String rootFailureType(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && depth++ < 16) {
            current = current.getCause();
        }
        return safeType(current == null ? null : current.getClass());
    }

    private String safeStack(Throwable failure) {
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = failure.getStackTrace();
        int limit = Math.min(frames.length, properties.getStackFrameLimit());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                stack.append('|');
            }
            stack.append(safeStackToken(frames[index].getClassName(), 180))
                    .append('#')
                    .append(safeStackToken(frames[index].getMethodName(), 100));
        }
        return stack.toString();
    }

    private static String safeStackToken(String value, int limit) {
        if (value == null || value.length() > limit
                || !value.matches("[A-Za-z0-9_.$<>]+")) {
            return "unknown";
        }
        return value;
    }

    private static void safeInfo(String template, Object... arguments) {
        try {
            LOGGER.info(template, arguments);
        } catch (RuntimeException ignored) {
            // 诊断后端异常不能建立额外订阅、改变 demand、替换流信号或中断业务调用。
        }
    }

    private static void safeWarn(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // 失败诊断自身异常不能覆盖真正的上游、协议或 Servlet 异常。
        }
    }

    private static void safeError(String template, Object... arguments) {
        try {
            LOGGER.error(template, arguments);
        } catch (RuntimeException ignored) {
            // 本地溢出诊断失败时仍必须让原始 OverflowException 原样传播。
        }
    }
}
