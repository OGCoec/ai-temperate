package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverSession;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverStateService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSnapshot;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSubscriber;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationSnapshotData;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewData;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 先订阅 Redis 通知再读取快照，并用 revision 去重补齐竞态；SSE 结束只提交 DETACHED 和延迟检查事件。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationObserverServiceImpl
        implements AiConversationGenerationObserverService {

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationGenerationOutputStore outputStore;
    private final AiConversationGenerationOutputSubscriber outputSubscriber;
    private final AiConversationGenerationObserverStateService observerStateService;
    private final AiConversationAsyncGenerationProperties asyncProperties;
    private final HybridBase64UrlCodec idCodec;
    private final PublicIdCodec publicIdCodec;
    private final ObjectMapper objectMapper;
    private final AiConversationStreamTimingDiagnosticService timingDiagnosticService;
    private final AiConversationStreamTimingClock timingClock;
    private final AiConversationStreamTransportDiagnosticService transportDiagnosticService;
    private final Clock clock;
    private final AiConversationMetrics metrics;
    private final AiConversationImagePreviewBroker previewBroker;

    public AiConversationGenerationObserverServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationOutputStore outputStore,
            AiConversationGenerationOutputSubscriber outputSubscriber,
            AiConversationGenerationObserverStateService observerStateService,
            AiConversationAsyncGenerationProperties asyncProperties,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationStreamTimingClock timingClock,
            AiConversationMetrics metrics,
            Clock clock) {
        this(
                generationMapper,
                outputStore,
                outputSubscriber,
                observerStateService,
                asyncProperties,
                idCodec,
                publicIdCodec,
                objectMapper,
                timingDiagnosticService,
                timingClock,
                metrics,
                clock,
                AiConversationStreamTransportDiagnosticService.noOp(),
                AiConversationImagePreviewBroker.noOp());
    }

    @Autowired
    public AiConversationGenerationObserverServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationOutputStore outputStore,
            AiConversationGenerationOutputSubscriber outputSubscriber,
            AiConversationGenerationObserverStateService observerStateService,
            AiConversationAsyncGenerationProperties asyncProperties,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationStreamTimingClock timingClock,
            AiConversationMetrics metrics,
            Clock clock,
            AiConversationStreamTransportDiagnosticService transportDiagnosticService,
            AiConversationImagePreviewBroker previewBroker) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.outputStore = Objects.requireNonNull(outputStore);
        this.outputSubscriber = Objects.requireNonNull(outputSubscriber);
        this.observerStateService = Objects.requireNonNull(observerStateService);
        this.asyncProperties = Objects.requireNonNull(asyncProperties);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.timingDiagnosticService = Objects.requireNonNull(timingDiagnosticService);
        this.timingClock = Objects.requireNonNull(timingClock);
        this.transportDiagnosticService = Objects.requireNonNull(transportDiagnosticService);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
        this.previewBroker = Objects.requireNonNull(previewBroker);
    }

    @Override
    @Transactional
    public AiConversationGenerationObserverSession observe(
            long userId,
            byte[] generationId) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (generationMapper.attachObserver(
                generationId,
                userId,
                AiConversationGenerationObserverStatus.ATTACHED.code(),
                now) != 1) {
            throw notFound();
        }
        AiConversationGeneration generation = generationMapper.findOwned(generationId, userId);
        if (generation == null) {
            throw notFound();
        }
        metrics.observerAttached();
        String publicId = idCodec.encode(generationId);
        long epoch = generation.getObserverEpoch();
        String traceId = currentTraceId();
        AiConversationStreamTimingContext timingContext =
                new AiConversationStreamTimingContext(
                        traceId,
                        idCodec.encode(generation.getUsageId()),
                        idCodec.encode(generation.getConversationId()),
                        publicIdCodec.encode(generation.getModelId()),
                        AiConversationStreamTimingPath.ASYNC_GENERATION_OBSERVER,
                        timingClock.nanoTime());
        Flux<AiConversationStreamEvent> events = Flux.defer(() ->
                        observerFlux(publicId, generation, timingContext))
                .doFinally(ignored -> observerStateService.detach(
                        userId, generationId, publicId, epoch, traceId));
        Flux<AiConversationStreamEvent> observed = timingDiagnosticService.observeBoundary(
                events,
                AiConversationStreamTimingBoundary.SSE_EVENT_READY,
                AiConversationGenerationObserverServiceImpl::eventTextCharacters);
        return new AiConversationGenerationObserverSession(
                publicId,
                idCodec.encode(generation.getUsageId()),
                epoch,
                timingDiagnosticService.withSession(observed, timingContext));
    }

    private static int eventTextCharacters(AiConversationStreamEvent event) {
        if (!"delta".equals(event.name()) || !(event.data() instanceof JsonNode data)) {
            return 0;
        }
        JsonNode text = data.get("text");
        return text == null || !text.isTextual() ? 0 : text.textValue().length();
    }

    private Flux<AiConversationStreamEvent> observerFlux(
            String generationPublicId,
            AiConversationGeneration generation,
            AiConversationStreamTimingContext timingContext) {
        Sinks.Many<AiConversationStreamEvent> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer();
        Object gate = new Object();
        AtomicBoolean initializing = new AtomicBoolean(true);
        AtomicLong deliveredRevision = new AtomicLong();
        List<AiConversationGenerationOutputEvent> pending = new ArrayList<>();
        final AutoCloseable subscription;
        try {
            subscription = outputSubscriber.subscribe(generationPublicId, event -> {
                transportDiagnosticService.recordSafely(
                        timingContext,
                        "ai_stream_redis_observer_received",
                        Map.of(
                                "generationPublicId", generationPublicId,
                                "revision", event.revision(),
                                "eventType", event.eventName()));
                synchronized (gate) {
                    if (initializing.get()) {
                        pending.add(event);
                    } else {
                        emitIfNew(sink, deliveredRevision, event);
                    }
                }
            });
        } catch (RuntimeException failure) {
            return Flux.error(failure);
        }

        boolean terminalSnapshot;
        synchronized (gate) {
            AiConversationGenerationOutputSnapshot snapshot = outputStore.snapshot(
                    generationPublicId);
            deliveredRevision.set(snapshot.revision());
            sink.tryEmitNext(new AiConversationStreamEvent(
                    "snapshot",
                    new AiConversationGenerationSnapshotData(
                            snapshot.revision(), snapshot.assistantText())));
            terminalSnapshot = snapshot.terminalEventName() != null;
            if (terminalSnapshot) {
                sink.tryEmitNext(event(
                        snapshot.terminalEventName(), snapshot.terminalDataJson()));
            } else if (generationStatus(generation.getGenerationStatus()).terminal()) {
                // Redis 是可丢失展示缓存；数据库已有资金终态时只补发展示事件，绝不再次触发结算。
                sink.tryEmitNext(databaseTerminalEvent(generationPublicId, generation));
                terminalSnapshot = true;
            }
            pending.stream()
                    .sorted(Comparator.comparingLong(
                            AiConversationGenerationOutputEvent::revision))
                    .forEach(item -> emitIfNew(sink, deliveredRevision, item));
            pending.clear();
            initializing.set(false);
        }
        if (terminalSnapshot) {
            sink.tryEmitComplete();
        }
        // 短心跳只用于尽快暴露真正失效的 TCP/SSE 写通道，页面隐藏但连接健康时不会改变 Generation 状态。
        Flux<AiConversationStreamEvent> heartbeat = Flux.interval(asyncProperties.observerHeartbeat())
                .map(ignored -> AiConversationStreamEvent.heartbeat());
        Flux<AiConversationStreamEvent> imagePreviews =
                Flux.defer(() -> previewBroker.events(generationPublicId))
                        .onErrorResume(RuntimeException.class, ignored -> Flux.empty())
                        .doOnNext(event -> recordImagePreviewCheckpoint(
                                timingContext,
                                generationPublicId,
                                event,
                                "P6_OBSERVER_RECEIVED"));
        return Flux.merge(sink.asFlux(), heartbeat, imagePreviews)
                .takeUntil(event -> terminalEvent(event.name()))
                // 浏览器断线只分离观察者，不能清空仍在生成的多槽位预览；只有业务终态才释放 Broker。
                .doOnNext(event -> {
                    recordImagePreviewCheckpoint(
                            timingContext,
                            generationPublicId,
                            event,
                            "P7_SSE_READY");
                    if (terminalEvent(event.name())) {
                        releasePreviewSafely(generationPublicId);
                    }
                })
                .doFinally(ignored -> {
                    closeQuietly(subscription);
                });
    }

    private void recordImagePreviewCheckpoint(
            AiConversationStreamTimingContext timingContext,
            String generationPublicId,
            AiConversationStreamEvent event,
            String checkpoint) {
        if (!"image-preview".equals(event.name())
                || !(event.data() instanceof AiConversationImagePreviewData data)) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("generationPublicId", generationPublicId);
        details.put("checkpoint", checkpoint);
        details.put("outputIndex", data.outputIndex());
        details.put("mappedPhase", data.phase());
        if (data.partialImageIndex() != null) {
            details.put("partialImageIndex", data.partialImageIndex());
        }
        transportDiagnosticService.recordSafely(
                timingContext,
                "ai_image_stream_checkpoint",
                details);
    }

    private void releasePreviewSafely(String generationPublicId) {
        try {
            previewBroker.release(generationPublicId);
        } catch (RuntimeException ignored) {
            // Broker 是本机临时预览旁路，释放失败由其生命周期上限兜底，不能破坏已准备写出的终态 SSE。
        }
    }

    private void emitIfNew(
            Sinks.Many<AiConversationStreamEvent> sink,
            AtomicLong deliveredRevision,
            AiConversationGenerationOutputEvent event) {
        boolean terminal = "completed".equals(event.eventName())
                || "error".equals(event.eventName())
                || "cancelled".equals(event.eventName());
        if (terminal || event.revision() > deliveredRevision.get()) {
            deliveredRevision.accumulateAndGet(event.revision(), Math::max);
            sink.tryEmitNext(event(event.eventName(), event.dataJson()));
            if (terminal) {
                sink.tryEmitComplete();
            }
        }
    }

    private AiConversationStreamEvent event(String eventName, String dataJson) {
        try {
            return new AiConversationStreamEvent(
                    eventName, objectMapper.readTree(dataJson));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("AI Generation output event is invalid.", exception);
        }
    }

    private AiConversationStreamEvent databaseTerminalEvent(
            String generationPublicId,
            AiConversationGeneration generation) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("generationPublicId", generationPublicId);
        data.put("usagePublicId", idCodec.encode(generation.getUsageId()));
        data.put("status", generationStatus(generation.getGenerationStatus()).name());
        data.put("terminalType", Objects.requireNonNullElse(
                generation.getTerminalType(), "unavailable"));
        data.put("terminalReason", Objects.requireNonNullElse(
                generation.getTerminalReason(), "unavailable"));
        return new AiConversationStreamEvent("completed", data);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignoredFailure) {
            // 本地监听器清理失败不能改变 Generation 或资金终态。
        }
    }

    private static boolean terminalEvent(String eventName) {
        return "completed".equals(eventName)
                || "error".equals(eventName)
                || "cancelled".equals(eventName);
    }

    private static AiConversationGenerationStatus generationStatus(int code) {
        for (AiConversationGenerationStatus status : AiConversationGenerationStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }

    private static AiConversationException notFound() {
        return new AiConversationException(
                AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                "生成任务不存在或不可用",
                false);
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "unavailable" : traceId;
    }
}
