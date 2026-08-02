package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationStreamDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.concurrent.Queues;

/**
 * 按订阅汇总四个流式边界的到达窗口、调度等待和集中爆发，只输出受控计数与公共关联 ID。
 *
 * <p>诊断关闭或未命中稳定采样时直接返回原 Flux；命中后也只使用 Reactor 操作符观察信号，
 * 不调用 subscribe、不缓存正文且任何探针异常都只关闭对应探针。</p>
 */
@Service
public final class AiConversationStreamTimingDiagnosticServiceImpl
        implements AiConversationStreamTimingDiagnosticService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationStreamTimingDiagnosticServiceImpl.class);
    private static final Object SESSION_CONTEXT_KEY = new Object();
    private static final int SCHEDULER_PENDING_LIMIT =
            Queues.SMALL_BUFFER_SIZE + 16;

    private final AiConversationStreamDiagnosticsProperties properties;
    private final AiConversationStreamTimingClock clock;

    public AiConversationStreamTimingDiagnosticServiceImpl(
            AiConversationStreamDiagnosticsProperties properties,
            AiConversationStreamTimingClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public <T> Flux<T> withSession(
            Flux<T> source,
            AiConversationStreamTimingContext context) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(context);
        if (!properties.shouldSample(context.usagePublicId())) {
            return source;
        }
        // 每次订阅创建独立状态；同一个冷 Flux 被重订阅时不会复用窗口、终态或调度器等待队列。
        return Flux.defer(() -> {
            TimingSession session = new TimingSession(properties, context, clock);
            // 最外层会话流负责最终汇总，使模型完成后的 FINALIZING/COMPLETED 边界仍能进入统计。
            return source
                    .doOnComplete(() -> session.terminate("COMPLETE", null))
                    .doOnError(failure -> session.terminate("ERROR", failure))
                    .doOnCancel(() -> session.terminate("CANCEL", null))
                    .contextWrite(current ->
                            current.put(SESSION_CONTEXT_KEY, session));
        });
    }

    @Override
    public <T> Flux<T> observeLifecycle(Flux<T> source) {
        Objects.requireNonNull(source);
        if (!properties.enabled()) {
            return source;
        }
        return Flux.deferContextual(context -> {
            TimingSession session = context.getOrDefault(
                    SESSION_CONTEXT_KEY, null);
            if (session == null) {
                return source;
            }
            return source
                    .doOnSubscribe(ignored -> session.subscribed())
                    .doOnComplete(() -> session.modelTerminated(
                            "COMPLETE", null))
                    .doOnError(failure -> session.modelTerminated(
                            "ERROR", failure))
                    .doOnCancel(() -> session.modelTerminated(
                            "CANCEL", null));
        });
    }

    @Override
    public <T> Flux<T> observeBoundary(
            Flux<T> source,
            AiConversationStreamTimingBoundary boundary,
            ToIntFunction<T> textCharacters) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(boundary);
        Objects.requireNonNull(textCharacters);
        if (!properties.enabled()) {
            return source;
        }
        return Flux.deferContextual(context -> {
            TimingSession session = context.getOrDefault(
                    SESSION_CONTEXT_KEY, null);
            if (session == null) {
                return source;
            }
            return source.doOnNext(value -> session.recordSafely(
                    boundary, value, textCharacters));
        });
    }

    /**
     * 保存一个订阅的全部计数；同步方法把模型线程、定时线程和调度器线程的统计更新串行化。
     */
    private static final class TimingSession {

        private final AiConversationStreamDiagnosticsProperties properties;
        private final AiConversationStreamTimingContext context;
        private final AiConversationStreamTimingClock clock;
        private final Map<AiConversationStreamTimingBoundary, BoundaryStats> stats =
                new EnumMap<>(AiConversationStreamTimingBoundary.class);
        private final EnumSet<AiConversationStreamTimingBoundary> disabled =
                EnumSet.noneOf(AiConversationStreamTimingBoundary.class);
        private final ArrayDeque<PendingSignal> schedulerPending =
                new ArrayDeque<>();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private long schedulerSequence;
        private long schedulerDelayMaximumNanos;
        private int schedulerPendingMaximum;
        private boolean schedulerPairingEnabled = true;
        private String latestBeforeThread = "unavailable";
        private String latestAfterThread = "unavailable";
        private long firstRawNanos = -1L;
        private long firstVisibleTextNanos = -1L;
        private String modelOutcome = "ACTIVE";
        private String modelFailureType = "none";

        private TimingSession(
                AiConversationStreamDiagnosticsProperties properties,
                AiConversationStreamTimingContext context,
                AiConversationStreamTimingClock clock) {
            this.properties = properties;
            this.context = context;
            this.clock = clock;
            for (AiConversationStreamTimingBoundary boundary
                    : AiConversationStreamTimingBoundary.values()) {
                stats.put(boundary, new BoundaryStats(context.startedNanos()));
            }
        }

        private void subscribed() {
            LOGGER.info(
                    "event=ai_stream_timing_subscribed traceId={} usagePublicId={} "
                            + "conversationPublicId={} modelPublicId={} path={}",
                    safe(context.traceId()),
                    safe(context.usagePublicId()),
                    safe(context.conversationPublicId()),
                    safe(context.modelPublicId()),
                    context.path());
        }

        private synchronized void modelTerminated(
                String outcome,
                Throwable failure) {
            if (!"ACTIVE".equals(modelOutcome)) {
                return;
            }
            modelOutcome = outcome;
            modelFailureType = failure == null
                    ? "none" : failure.getClass().getName();
            LOGGER.info(
                    "event=ai_stream_timing_model_terminal traceId={} "
                            + "usagePublicId={} path={} outcome={} failureType={} elapsedMs={}",
                    safe(context.traceId()),
                    safe(context.usagePublicId()),
                    context.path(),
                    modelOutcome,
                    modelFailureType,
                    millis(clock.nanoTime() - context.startedNanos()));
        }

        private <T> void recordSafely(
                AiConversationStreamTimingBoundary boundary,
                T value,
                ToIntFunction<T> textCharacters) {
            synchronized (this) {
                if (terminated.get() || disabled.contains(boundary)) {
                    return;
                }
                try {
                    int characters = Math.max(0, textCharacters.applyAsInt(value));
                    record(boundary, characters, clock.nanoTime());
                } catch (RuntimeException probeFailure) {
                    disabled.add(boundary);
                    LOGGER.error(
                            "event=ai_stream_timing_probe_disabled traceId={} "
                                    + "usagePublicId={} path={} boundary={} exceptionType={}",
                            safe(context.traceId()),
                            safe(context.usagePublicId()),
                            context.path(),
                            boundary,
                            probeFailure.getClass().getName());
                }
            }
        }

        private void record(
                AiConversationStreamTimingBoundary boundary,
                int textCharacters,
                long nowNanos) {
            if (boundary == AiConversationStreamTimingBoundary.SPRING_AI_RAW) {
                enqueueSchedulerSignal(nowNanos);
                if (firstRawNanos < 0L) {
                    firstRawNanos = nowNanos;
                    logFirst("ai_stream_timing_first_raw", boundary, nowNanos);
                }
            } else if (boundary
                    == AiConversationStreamTimingBoundary.AFTER_BOUNDED_ELASTIC) {
                pairSchedulerSignal(nowNanos);
            }
            if (textCharacters > 0 && firstVisibleTextNanos < 0L) {
                firstVisibleTextNanos = nowNanos;
                logFirst("ai_stream_timing_first_text", boundary, nowNanos);
            }
            BoundaryStats boundaryStats = stats.get(boundary);
            long silenceBeforeNanos = boundaryStats.record(
                    nowNanos, textCharacters);
            detectBurst(boundary, boundaryStats, nowNanos, silenceBeforeNanos);
            if (boundaryStats.windowChunkCount >= properties.logEveryChunks()
                    || nowNanos - boundaryStats.windowStartedNanos
                    >= properties.window().toNanos()) {
                logWindow(boundary, boundaryStats, nowNanos, false);
                boundaryStats.resetWindow(nowNanos);
            }
        }

        private void enqueueSchedulerSignal(long nowNanos) {
            if (!schedulerPairingEnabled) {
                return;
            }
            if (schedulerPending.size() >= SCHEDULER_PENDING_LIMIT) {
                disableSchedulerPairing("pending_limit");
                return;
            }
            schedulerPending.addLast(new PendingSignal(
                    ++schedulerSequence,
                    nowNanos,
                    safeThread(Thread.currentThread().getName())));
            schedulerPendingMaximum = Math.max(
                    schedulerPendingMaximum, schedulerPending.size());
        }

        private void pairSchedulerSignal(long nowNanos) {
            if (!schedulerPairingEnabled) {
                return;
            }
            PendingSignal pending = schedulerPending.pollFirst();
            if (pending == null) {
                disableSchedulerPairing("sequence_missing");
                return;
            }
            schedulerDelayMaximumNanos = Math.max(
                    schedulerDelayMaximumNanos,
                    Math.max(0L, nowNanos - pending.enteredNanos()));
            latestBeforeThread = pending.threadName();
            latestAfterThread = safeThread(Thread.currentThread().getName());
        }

        private void disableSchedulerPairing(String reason) {
            schedulerPairingEnabled = false;
            schedulerPending.clear();
            LOGGER.error(
                    "event=ai_stream_timing_scheduler_probe_disabled traceId={} "
                            + "usagePublicId={} path={} reason={}",
                    safe(context.traceId()),
                    safe(context.usagePublicId()),
                    context.path(),
                    reason);
        }

        private void detectBurst(
                AiConversationStreamTimingBoundary boundary,
                BoundaryStats boundaryStats,
                long nowNanos,
                long silenceBeforeNanos) {
            if (silenceBeforeNanos >= properties.silenceThreshold().toNanos()) {
                boundaryStats.burstStartedNanos = nowNanos;
                boundaryStats.burstSilenceNanos = silenceBeforeNanos;
                boundaryStats.burstChunkCount = 1L;
                boundaryStats.burstLogged = false;
            } else if (boundaryStats.burstStartedNanos >= 0L) {
                if (nowNanos - boundaryStats.burstStartedNanos
                        <= properties.burstWindow().toNanos()) {
                    boundaryStats.burstChunkCount++;
                } else {
                    boundaryStats.clearBurstCandidate();
                }
            }
            if (!boundaryStats.burstLogged
                    && boundaryStats.burstStartedNanos >= 0L
                    && boundaryStats.burstChunkCount >= properties.burstChunks()) {
                boundaryStats.burstLogged = true;
                LOGGER.warn(
                        "event=ai_stream_timing_burst traceId={} usagePublicId={} "
                                + "conversationPublicId={} modelPublicId={} path={} boundary={} "
                                + "elapsedMs={} silenceBeforeMs={} windowMs={} "
                                + "chunkCount={} textChars={} schedulerDelayMaxMs={} "
                                + "pendingChunkMax={} beforeThread={} afterThread={} burst=true",
                        safe(context.traceId()),
                        safe(context.usagePublicId()),
                        safe(context.conversationPublicId()),
                        safe(context.modelPublicId()),
                        context.path(),
                        boundary,
                        millis(nowNanos - context.startedNanos()),
                        millis(boundaryStats.burstSilenceNanos),
                        millis(nowNanos - boundaryStats.burstStartedNanos),
                        boundaryStats.burstChunkCount,
                        boundaryStats.windowTextCharacters,
                        millis(schedulerDelayMaximumNanos),
                        schedulerPendingMaximum,
                        latestBeforeThread,
                        latestAfterThread);
            }
        }

        private void logFirst(
                String event,
                AiConversationStreamTimingBoundary boundary,
                long nowNanos) {
            LOGGER.info(
                    "event={} traceId={} usagePublicId={} conversationPublicId={} "
                            + "modelPublicId={} path={} boundary={} elapsedMs={} thread={}",
                    event,
                    safe(context.traceId()),
                    safe(context.usagePublicId()),
                    safe(context.conversationPublicId()),
                    safe(context.modelPublicId()),
                    context.path(),
                    boundary,
                    millis(nowNanos - context.startedNanos()),
                    safeThread(Thread.currentThread().getName()));
        }

        private void logWindow(
                AiConversationStreamTimingBoundary boundary,
                BoundaryStats boundaryStats,
                long nowNanos,
                boolean terminal) {
            if (boundaryStats.windowChunkCount == 0L) {
                return;
            }
            LOGGER.info(
                    "event=ai_stream_timing_window traceId={} usagePublicId={} "
                            + "conversationPublicId={} modelPublicId={} path={} boundary={} "
                            + "elapsedMs={} silenceBeforeMs={} windowMs={} "
                            + "chunkCount={} textChars={} schedulerDelayMaxMs={} "
                            + "pendingChunkMax={} thread={} burst={} terminal={}",
                    safe(context.traceId()),
                    safe(context.usagePublicId()),
                    safe(context.conversationPublicId()),
                    safe(context.modelPublicId()),
                    context.path(),
                    boundary,
                    millis(nowNanos - context.startedNanos()),
                    millis(boundaryStats.windowMaximumSilenceNanos),
                    millis(nowNanos - boundaryStats.windowStartedNanos),
                    boundaryStats.windowChunkCount,
                    boundaryStats.windowTextCharacters,
                    millis(schedulerDelayMaximumNanos),
                    schedulerPendingMaximum,
                    safeThread(Thread.currentThread().getName()),
                    boundaryStats.burstLogged,
                    terminal);
        }

        private void terminate(String outerOutcome, Throwable failure) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                long nowNanos = clock.nanoTime();
                for (Map.Entry<AiConversationStreamTimingBoundary, BoundaryStats>
                        entry : stats.entrySet()) {
                    logWindow(entry.getKey(), entry.getValue(), nowNanos, true);
                }
                LOGGER.info(
                        "event=ai_stream_timing_summary traceId={} usagePublicId={} "
                                + "conversationPublicId={} modelPublicId={} path={} outcome={} "
                                + "failureType={} elapsedMs={} firstRawMs={} "
                                + "firstVisibleTextMs={} rawChunks={} schedulerChunks={} "
                                + "batcherChunks={} sseEvents={} textChars={} "
                                + "schedulerDelayMaxMs={} pendingChunkMax={} "
                                + "schedulerPairingEnabled={}",
                        safe(context.traceId()),
                        safe(context.usagePublicId()),
                        safe(context.conversationPublicId()),
                        safe(context.modelPublicId()),
                        context.path(),
                        "ACTIVE".equals(modelOutcome)
                                ? outerOutcome : modelOutcome,
                        "none".equals(modelFailureType) && failure != null
                                ? failure.getClass().getName()
                                : modelFailureType,
                        millis(nowNanos - context.startedNanos()),
                        sinceStart(firstRawNanos),
                        sinceStart(firstVisibleTextNanos),
                        stats.get(AiConversationStreamTimingBoundary.SPRING_AI_RAW)
                                .totalChunkCount,
                        stats.get(AiConversationStreamTimingBoundary.AFTER_BOUNDED_ELASTIC)
                                .totalChunkCount,
                        stats.get(AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER)
                                .totalChunkCount,
                        stats.get(AiConversationStreamTimingBoundary.SSE_EVENT_READY)
                                .totalChunkCount,
                        stats.get(AiConversationStreamTimingBoundary.SSE_EVENT_READY)
                                .totalTextCharacters,
                        millis(schedulerDelayMaximumNanos),
                        schedulerPendingMaximum,
                        schedulerPairingEnabled);
                schedulerPending.clear();
            }
        }

        private long sinceStart(long valueNanos) {
            return valueNanos < 0L
                    ? -1L : millis(valueNanos - context.startedNanos());
        }
    }

    /**
     * 保存单个边界的累计值、当前限频窗口和一次静默后爆发候选。
     */
    private static final class BoundaryStats {

        private long totalChunkCount;
        private long totalTextCharacters;
        private long windowStartedNanos;
        private long windowChunkCount;
        private long windowTextCharacters;
        private long windowMaximumSilenceNanos;
        private long lastSignalNanos;
        private long burstStartedNanos = -1L;
        private long burstSilenceNanos;
        private long burstChunkCount;
        private boolean burstLogged;

        private BoundaryStats(long startedNanos) {
            windowStartedNanos = startedNanos;
            lastSignalNanos = startedNanos;
        }

        private long record(long nowNanos, int textCharacters) {
            long silence = Math.max(0L, nowNanos - lastSignalNanos);
            totalChunkCount++;
            totalTextCharacters += textCharacters;
            windowChunkCount++;
            windowTextCharacters += textCharacters;
            windowMaximumSilenceNanos = Math.max(
                    windowMaximumSilenceNanos, silence);
            lastSignalNanos = nowNanos;
            return silence;
        }

        private void resetWindow(long nowNanos) {
            windowStartedNanos = nowNanos;
            windowChunkCount = 0L;
            windowTextCharacters = 0L;
            windowMaximumSilenceNanos = 0L;
        }

        private void clearBurstCandidate() {
            burstStartedNanos = -1L;
            burstSilenceNanos = 0L;
            burstChunkCount = 0L;
            burstLogged = false;
        }
    }

    /**
     * 只记录 publishOn 前信号的诊断序号、进入时间和线程名，不保存信号对象或模型内容。
     */
    private record PendingSignal(
            long sequence,
            long enteredNanos,
            String threadName) {
    }

    private static long millis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return "unavailable";
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)
                    && current != '-'
                    && current != '_') {
                return "unavailable";
            }
        }
        return value;
    }

    private static String safeThread(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 64));
        for (int index = 0; index < value.length() && safe.length() < 64; index++) {
            char current = value.charAt(index);
            safe.append(Character.isLetterOrDigit(current)
                    || current == '-' || current == '_' || current == '.'
                    ? current : '_');
        }
        return safe.toString();
    }
}
