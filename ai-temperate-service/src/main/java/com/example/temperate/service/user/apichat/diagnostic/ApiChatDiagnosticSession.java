package com.example.temperate.service.user.apichat.diagnostic;

import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.Normalization;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.SignalType;

/**
 * 该会话是来以线程安全、有界且不含业务正文的方式汇总单条 API Chat 流的边界计数、节奏和终止原因。
 * 会话只保存帧长度、类别、相对时间和安全枚举；任何请求内容、输出内容、工具参数、IP、Cookie 与 API Key 都不得进入日志。
 */
public final class ApiChatDiagnosticSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiChatDiagnosticSession.class);
    private static final String DIAGNOSTIC_SCHEMA = "chat-diag-v1";
    private static final ApiChatDiagnosticSession DISABLED = new ApiChatDiagnosticSession();

    private final boolean enabled;
    private final boolean sampled;
    private final String traceId;
    private final ApiKeyProperties.StreamDiagnostics properties;
    private final ApiChatDiagnosticClock clock;
    private final long startedNanos;
    private final Map<ApiChatDiagnosticBoundary, Long> boundaryCounts =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> boundaryByteCounts =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> lastFrameNanos =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> burstStartedNanos =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Integer> burstFrameCounts =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> windowStartedNanos =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> windowFrameCounts =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Map<ApiChatDiagnosticBoundary, Long> windowByteCounts =
            new EnumMap<>(ApiChatDiagnosticBoundary.class);
    private final Deque<String> terminalHistory = new ArrayDeque<>();
    private final AtomicBoolean summarized = new AtomicBoolean();

    private boolean subscribed;
    private boolean requestRecorded;
    private boolean usageSeen;
    private boolean usageForwarded;
    private boolean doneSeen;
    private ApiChatDiagnosticStage observedStage;
    private boolean validationCompleted;
    private boolean upstreamAttempted;
    private int upstreamStatus = -1;
    private String upstreamContentType = "unknown";
    private boolean upstreamSse;
    private String failureType = "none";
    private String rootFailureType = "none";
    private ApiChatProtocolViolation protocolViolation;
    private ApiChatUpstreamFailure upstreamFailure;
    private String failureStage = "none";

    private ApiChatDiagnosticSession() {
        this.enabled = false;
        this.sampled = false;
        this.traceId = "disabled";
        this.properties = null;
        this.clock = System::nanoTime;
        this.startedNanos = 0L;
    }

    public ApiChatDiagnosticSession(
            ApiKeyProperties.StreamDiagnostics properties,
            ApiChatDiagnosticClock clock) {
        this(properties, clock, true);
    }

    /**
     * 创建启用的诊断会话；未命中成功采样时仍保留有界状态，只在发生失败后输出完整终态证据。
     */
    public ApiChatDiagnosticSession(
            ApiKeyProperties.StreamDiagnostics properties,
            ApiChatDiagnosticClock clock,
            boolean sampled) {
        this.enabled = true;
        this.sampled = sampled;
        String requestTrace = MDC.get("apiChatTraceId");
        this.traceId = requestTrace == null || requestTrace.isBlank()
                ? UUID.randomUUID().toString() : requestTrace;
        this.properties = properties;
        this.clock = clock;
        this.startedNanos = clock.nanoTime();
        for (ApiChatDiagnosticBoundary boundary : ApiChatDiagnosticBoundary.values()) {
            boundaryCounts.put(boundary, 0L);
            boundaryByteCounts.put(boundary, 0L);
            lastFrameNanos.put(boundary, startedNanos);
            burstStartedNanos.put(boundary, startedNanos);
            burstFrameCounts.put(boundary, 0);
            windowStartedNanos.put(boundary, startedNanos);
            windowFrameCounts.put(boundary, 0L);
            windowByteCounts.put(boundary, 0L);
        }
        if (sampled) {
            safeInfo(
                    "event=api_chat_stream_start diagnosticSchema={} traceId={} diagnostics=true",
                    DIAGNOSTIC_SCHEMA,
                    traceId);
        }
    }

    public static ApiChatDiagnosticSession disabled() {
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

    public synchronized void recordStageEnter(ApiChatDiagnosticStage stage) {
        if (enabled) {
            if (observedStage == null) {
                observedStage = stage;
            }
            if (sampled) {
                safeInfo("event=api_chat_stage_enter traceId={} stage={}", traceId, stage);
            }
        }
    }

    public synchronized void recordStageReturn(
            ApiChatDiagnosticStage stage,
            long elapsedNanos,
            Object result) {
        if (enabled) {
            if (stage == ApiChatDiagnosticStage.COMPLETION_SERVICE) {
                validationCompleted = true;
            }
            if (sampled) {
                safeInfo(
                        "event=api_chat_stage_return traceId={} stage={} elapsedMs={} resultType={}",
                        traceId,
                        stage,
                        millis(elapsedNanos),
                        result == null ? "null" : safeType(result.getClass()));
            }
        }
    }

    public synchronized void recordStageFailure(ApiChatDiagnosticStage stage) {
        if (enabled) {
            failureStage = stage == null ? "unknown" : stage.name();
        }
    }

    public synchronized void recordUpstreamAttempted() {
        if (enabled && !upstreamAttempted) {
            upstreamAttempted = true;
            if (sampled) {
                safeInfo(
                        "event=api_chat_upstream_attempted traceId={} elapsedMs={}",
                        traceId,
                        elapsedMillis());
            }
        }
    }

    public synchronized void summarizeSynchronous(ApiChatDiagnosticStage stage) {
        if (enabled && sampled) {
            safeInfo(
                    "event=api_chat_sync_summary traceId={} stage={} elapsedMs={} outcome=RETURNED",
                    traceId,
                    stage,
                    elapsedMillis());
        }
    }

    public synchronized void recordSubscribed() {
        if (!enabled || subscribed) {
            return;
        }
        subscribed = true;
        if (sampled) {
            safeInfo("event=api_chat_stream_subscribed traceId={} elapsedMs={}",
                    traceId, elapsedMillis());
        }
    }

    /**
     * 请求形状只记录集合数量、布尔开关和受限模型名；消息 content、工具定义、工具参数以及不可逆 Key 摘要均不读取也不输出。
     */
    public synchronized void recordRequest(
            ApiKeyPrincipal principal,
            ApiChatRequest request) {
        if (!enabled || !sampled || requestRecorded || principal == null || request == null) {
            return;
        }
        requestRecorded = true;
        int messages = request.messages() == null ? 0 : request.messages().size();
        int tools = request.tools() == null ? 0 : request.tools().size();
        boolean includeUsage = request.streamOptions() != null
                && request.streamOptions().includeUsage() != null
                && request.streamOptions().includeUsage().isBoolean()
                && request.streamOptions().includeUsage().booleanValue();
        safeInfo(
                "event=api_chat_request_shape traceId={} accountId={} model={} messages={} tools={} authorizedModels={} streamBoolean={} includeUsage={} hasTokenLimit={} hasToolChoice={}",
                traceId,
                principal.loginIdentityId(),
                safeToken(request.model(), 96),
                messages,
                tools,
                principal.modelIds().size(),
                request.stream() != null && request.stream().isBoolean(),
                includeUsage,
                request.maxCompletionTokens() != null || request.maxTokens() != null,
                request.toolChoice() != null);
    }

    public synchronized void recordUpstreamHeaders(
            int status,
            String contentType,
            boolean sseCompatible) {
        if (!enabled) {
            return;
        }
        upstreamStatus = status;
        upstreamContentType = safeContentType(contentType);
        upstreamSse = sseCompatible;
        if (sampled) {
            safeInfo(
                    "event=api_chat_upstream_headers traceId={} status={} contentType={} sse={}",
                    traceId,
                    status,
                    upstreamContentType,
                    sseCompatible);
        }
    }

    public synchronized void recordBoundary(
            ApiChatDiagnosticBoundary boundary,
            long bytes,
            ApiChatFrameKind kind) {
        if (!enabled) {
            return;
        }
        long now = clock.nanoTime();
        long gapNanos = Math.max(0L, now - lastFrameNanos.get(boundary));
        lastFrameNanos.put(boundary, now);
        long count = saturatedAdd(boundaryCounts.get(boundary), 1L);
        boundaryCounts.put(boundary, count);
        boundaryByteCounts.put(
                boundary,
                saturatedAdd(boundaryByteCounts.get(boundary), Math.max(0L, bytes)));
        recordWindow(boundary, now, Math.max(0L, bytes));
        remember(boundary + ":" + kind + ":" + Math.max(0L, bytes)
                + ":" + millis(gapNanos));

        if (sampled && gapNanos >= properties.getSilenceThreshold().toNanos()) {
            safeInfo(
                    "event=api_chat_stream_silence traceId={} boundary={} gapMs={} frame={}",
                    traceId,
                    boundary,
                    millis(gapNanos),
                    count);
        }
        long burstStarted = burstStartedNanos.get(boundary);
        int burstFrames = burstFrameCounts.get(boundary);
        if (now - burstStarted > properties.getBurstWindow().toNanos()) {
            burstStartedNanos.put(boundary, now);
            burstFrames = 0;
        }
        burstFrames++;
        burstFrameCounts.put(boundary, burstFrames);
        if (sampled && burstFrames == properties.getBurstFrames()) {
            safeInfo(
                    "event=api_chat_stream_burst traceId={} boundary={} frames={} windowMs={}",
                    traceId,
                    boundary,
                    burstFrames,
                    properties.getBurstWindow().toMillis());
        }
        if (sampled && (count == 1L || count % properties.getLogEveryFrames() == 0L
                || kind == ApiChatFrameKind.USAGE || kind == ApiChatFrameKind.DONE)) {
            safeInfo(
                    "event=api_chat_stream_frame traceId={} boundary={} frame={} kind={} bytes={} gapMs={}",
                    traceId,
                    boundary,
                    count,
                    kind,
                    Math.max(0L, bytes),
                    millis(gapNanos));
        }
    }

    public synchronized void recordUsage(ApiInferenceUsage usage, boolean forwarded) {
        if (!enabled) {
            return;
        }
        usageSeen = true;
        usageForwarded = forwarded;
        if (sampled) {
            safeInfo(
                    "event=api_chat_usage_seen traceId={} promptTokens={} completionTokens={} cachedTokens={} forwarded={}",
                    traceId,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                    forwarded);
        }
    }

    /** 归一化日志只记录固定枚举和帧数，不接收也不输出任何上游 JSON。 */
    public synchronized void recordNormalization(
            Normalization normalization,
            int normalizedFrames) {
        if (!enabled || normalization == null || normalization == Normalization.NONE) {
            return;
        }
        if (sampled) {
            safeInfo(
                    "event=api_chat_protocol_normalized traceId={} normalization={} normalizedFrames={}",
                    traceId,
                    normalization,
                    Math.max(0, normalizedFrames));
        }
    }

    public synchronized void recordDone() {
        if (enabled) {
            doneSeen = true;
            if (sampled) {
                safeInfo("event=api_chat_done_seen traceId={} elapsedMs={}",
                        traceId, elapsedMillis());
            }
        }
    }

    public synchronized void recordFailure(Throwable failure) {
        if (!enabled || failure == null) {
            return;
        }
        failureType = safeType(failure.getClass());
        rootFailureType = rootFailureType(failure);
        protocolViolation = findViolation(failure);
        upstreamFailure = findUpstreamFailure(failure);
        safeWarn(
                "event=api_chat_stream_failure traceId={} elapsedMs={} failureType={} rootFailureType={} protocolViolation={} upstreamFailure={} stack={}",
                traceId,
                elapsedMillis(),
                failureType,
                rootFailureType,
                protocolViolation == null ? "none" : protocolViolation,
                upstreamFailure == null ? "none" : upstreamFailure,
                safeStack(failure));
    }

    public void summarize(SignalType signalType) {
        boolean failureSignal = signalType == SignalType.ON_ERROR;
        if (!enabled || (!sampled && !failureSignal)
                || !summarized.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            boolean failed = failureSignal || !"none".equals(failureType);
            safeInfo(
                    "event=api_chat_stream_summary diagnosticSchema={} traceId={} stage={} signal={} elapsedMs={} rawFrames={} rawBytes={} parsedFrames={} parsedBytes={} gatedFrames={} gatedBytes={} readyFrames={} readyBytes={} usageSeen={} usageForwarded={} doneSeen={} validationCompleted={} upstreamAttempted={} upstreamStatus={} upstreamContentType={} upstreamSse={} failureStage={} failureType={} rootFailureType={} protocolViolation={} upstreamFailure={}",
                    DIAGNOSTIC_SCHEMA,
                    traceId,
                    observedStage == null ? "unknown" : observedStage,
                    signalType,
                    elapsedMillis(),
                    count(ApiChatDiagnosticBoundary.UPSTREAM_RAW),
                    bytes(ApiChatDiagnosticBoundary.UPSTREAM_RAW),
                    count(ApiChatDiagnosticBoundary.AFTER_PROTOCOL_PARSE),
                    bytes(ApiChatDiagnosticBoundary.AFTER_PROTOCOL_PARSE),
                    count(ApiChatDiagnosticBoundary.AFTER_BUSINESS_GATE),
                    bytes(ApiChatDiagnosticBoundary.AFTER_BUSINESS_GATE),
                    count(ApiChatDiagnosticBoundary.SSE_EVENT_READY),
                    bytes(ApiChatDiagnosticBoundary.SSE_EVENT_READY),
                    usageSeen,
                    usageForwarded,
                    doneSeen,
                    validationCompleted,
                    upstreamAttempted,
                    upstreamStatus,
                    upstreamContentType,
                    upstreamSse,
                    failureStage,
                    failureType,
                    rootFailureType,
                    protocolViolation == null ? "none" : protocolViolation,
                    upstreamFailure == null ? "none" : upstreamFailure);
            if (failed) {
                logTerminalHistoryParts();
            }
        }
    }

    /** 失败历史每行最多八项，避免日志平台截断后看不到真正的终态边界。 */
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
                    "event=api_chat_stream_terminal_history traceId={} part={} parts={} entries={}",
                    traceId,
                    part + 1,
                    parts,
                    String.join(",", history.subList(from, to)));
        }
    }

    private long count(ApiChatDiagnosticBoundary boundary) {
        return boundaryCounts.getOrDefault(boundary, 0L);
    }

    private long bytes(ApiChatDiagnosticBoundary boundary) {
        return boundaryByteCounts.getOrDefault(boundary, 0L);
    }

    private void recordWindow(
            ApiChatDiagnosticBoundary boundary,
            long now,
            long bytes) {
        long windowStarted = windowStartedNanos.get(boundary);
        if (now - windowStarted >= properties.getWindow().toNanos()) {
            if (sampled) {
                safeInfo(
                        "event=api_chat_stream_window traceId={} boundary={} elapsedMs={} frames={} bytes={}",
                        traceId,
                        boundary,
                        millis(now - windowStarted),
                        windowFrameCounts.get(boundary),
                        windowByteCounts.get(boundary));
            }
            windowStartedNanos.put(boundary, now);
            windowFrameCounts.put(boundary, 0L);
            windowByteCounts.put(boundary, 0L);
        }
        windowFrameCounts.put(
                boundary,
                saturatedAdd(windowFrameCounts.get(boundary), 1L));
        windowByteCounts.put(
                boundary,
                saturatedAdd(windowByteCounts.get(boundary), bytes));
    }

    private static long saturatedAdd(long current, long increment) {
        return Long.MAX_VALUE - current < increment
                ? Long.MAX_VALUE : current + increment;
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

    private static long millis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toMillis();
    }

    private static String safeType(Class<?> type) {
        String name = type == null ? "unknown" : type.getName();
        return name.matches("[A-Za-z0-9_.$]{1,200}") ? name : "unknown";
    }

    private static String safeToken(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.length() <= maximumLength
                && normalized.matches("[a-z0-9_./+;= -]+")
                ? normalized.replace(' ', '_') : "unknown";
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 96
                && normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? normalized : "unknown";
    }

    private static ApiChatProtocolViolation findViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiChatProtocolViolationException violation) {
                return violation.violation();
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
        return safeType(current == null ? null : current.getClass());
    }

    private static ApiChatUpstreamFailure findUpstreamFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiChatUpstreamFailureException upstream) {
                return upstream.failure();
            }
            current = current.getCause();
        }
        return null;
    }

    private String safeStack(Throwable failure) {
        StringBuilder result = new StringBuilder();
        StackTraceElement[] frames = failure.getStackTrace();
        int limit = Math.min(frames.length, properties.getStackFrameLimit());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                result.append('|');
            }
            result.append(safeToken(frames[index].getClassName(), 180))
                    .append('#')
                    .append(safeToken(frames[index].getMethodName(), 100));
        }
        return result.toString();
    }

    private static void safeInfo(String template, Object... arguments) {
        try {
            LOGGER.info(template, arguments);
        } catch (RuntimeException ignored) {
            // 诊断后端异常不能建立额外订阅、改变流信号或中断同步业务调用。
        }
    }

    private static void safeWarn(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // 失败诊断自身异常不能替换真正的业务或上游异常。
        }
    }
}
