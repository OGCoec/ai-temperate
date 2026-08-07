package com.example.temperate.service.user.aiconversation.context.event.impl;

import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionOperation;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStateStore;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationContextUsageProperties;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEvent;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventData;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventNotification;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventService;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventSubscriber;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 先订阅 Redis 通知再读取权威用量快照，并用 eventRevision 去重补齐建流竞态。
 */
@Service
public final class AiConversationContextEventServiceImpl
        implements AiConversationContextEventService {

    private final AiConversationContextUsageService usageService;
    private final AiConversationCompactionStateStore stateStore;
    private final AiConversationContextEventSubscriber eventSubscriber;
    private final AiConversationContextUsageProperties properties;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationContextEventServiceImpl(
            AiConversationContextUsageService usageService,
            AiConversationCompactionStateStore stateStore,
            AiConversationContextEventSubscriber eventSubscriber,
            AiConversationContextUsageProperties properties,
            Clock clock,
            AiConversationMetrics metrics) {
        this.usageService = Objects.requireNonNull(usageService);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.eventSubscriber = Objects.requireNonNull(eventSubscriber);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Flux<AiConversationContextEvent> observe(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId,
            long afterRevision) {
        if (afterRevision < 0L) {
            throw new IllegalArgumentException(
                    "AI context event revision must not be negative.");
        }
        return Flux.defer(() -> observerFlux(
                userId,
                conversationId.clone(),
                conversationPublicId,
                modelPublicId,
                afterRevision));
    }

    @Override
    public long publishUsage(
            String conversationPublicId,
            long contextRevision) {
        return stateStore.publishUsage(conversationPublicId, contextRevision);
    }

    private Flux<AiConversationContextEvent> observerFlux(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId,
            long afterRevision) {
        Sinks.Many<AiConversationContextEvent> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer();
        Object gate = new Object();
        AtomicBoolean initializing = new AtomicBoolean(true);
        AtomicLong deliveredRevision = new AtomicLong(afterRevision);
        List<AiConversationContextEventNotification> pending = new ArrayList<>();
        AutoCloseable subscription = eventSubscriber.subscribe(
                conversationPublicId,
                event -> {
                    synchronized (gate) {
                        if (initializing.get()) {
                            pending.add(event);
                        } else {
                            emitIfNew(
                                    sink,
                                    deliveredRevision,
                                    event,
                                    conversationId,
                                    conversationPublicId,
                                    modelPublicId);
                        }
                    }
                });

        try {
            synchronized (gate) {
                // 归属检查必须发生在订阅建立后，避免授权通过到快照读取之间漏掉快速压缩终态。
                AiConversationContextUsage snapshot = usageService.getOwned(
                        userId,
                        conversationId,
                        conversationPublicId,
                        modelPublicId);
                AiConversationCompactionOperation operation = stateStore
                        .find(conversationPublicId)
                        .orElse(AiConversationCompactionOperation.idle(
                                deliveredRevision.get()));
                deliveredRevision.accumulateAndGet(
                        operation.eventRevision(), Math::max);
                sink.tryEmitNext(event(
                        "context_snapshot",
                        deliveredRevision.get(),
                        snapshot,
                        operation));
                pending.stream()
                        .sorted(Comparator.comparingLong(
                                AiConversationContextEventNotification::eventRevision))
                        .forEach(item -> emitIfNew(
                                sink,
                                deliveredRevision,
                                item,
                                conversationId,
                                conversationPublicId,
                                modelPublicId));
                pending.clear();
                initializing.set(false);
            }
        } catch (RuntimeException initializationFailure) {
            closeQuietly(subscription);
            throw initializationFailure;
        }

        Flux<AiConversationContextEvent> heartbeat = Flux.interval(
                        properties.eventHeartbeat())
                .map(ignored -> new AiConversationContextEvent(
                        "heartbeat",
                        new AiConversationContextEventData(
                                deliveredRevision.get(),
                                0L,
                                conversationPublicId,
                                modelPublicId,
                                null,
                                null,
                                null,
                                null,
                                now(),
                                false,
                                null)));
        Mono<AiConversationContextEvent> timeout = Mono.fromSupplier(() ->
                new AiConversationContextEvent(
                        "timeout",
                        new AiConversationContextEventData(
                                deliveredRevision.get(),
                                0L,
                                conversationPublicId,
                                modelPublicId,
                                null,
                                null,
                                null,
                                null,
                                now(),
                                true,
                                "AI_CONTEXT_EVENT_TIMEOUT")));
        metrics.contextEventsOpened();
        return Flux.merge(sink.asFlux(), heartbeat)
                .take(properties.eventTimeout())
                .concatWith(timeout)
                .takeUntil(AiConversationContextEventServiceImpl::terminal)
                .doFinally(ignored -> {
                    closeQuietly(subscription);
                    metrics.contextEventsClosed();
                });
    }

    private void emitIfNew(
            Sinks.Many<AiConversationContextEvent> sink,
            AtomicLong deliveredRevision,
            AiConversationContextEventNotification notification,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId) {
        if (notification.eventRevision() <= deliveredRevision.get()) {
            return;
        }
        deliveredRevision.set(notification.eventRevision());
        try {
            AiConversationContextUsage usage = usageService.get(
                    conversationId,
                    conversationPublicId,
                    modelPublicId);
            AiConversationCompactionOperation operation = stateStore
                    .find(conversationPublicId)
                    .orElse(AiConversationCompactionOperation.idle(
                            notification.eventRevision()));
            AiConversationContextEvent emitted = event(
                    notification.eventType(),
                    notification.eventRevision(),
                    usage,
                    operation);
            sink.tryEmitNext(emitted);
            if (terminal(emitted)) {
                sink.tryEmitComplete();
            }
        } catch (RuntimeException ignoredFailure) {
            // 通知是易失展示旁路；下一次重连仍会从 Redis 与 PostgreSQL 重新读取权威快照。
        }
    }

    private AiConversationContextEvent event(
            String name,
            long eventRevision,
            AiConversationContextUsage usage,
            AiConversationCompactionOperation operation) {
        boolean snapshot = "context_snapshot".equals(name);
        return new AiConversationContextEvent(
                name,
                new AiConversationContextEventData(
                        eventRevision,
                        usage.contextRevision(),
                        usage.conversationPublicId(),
                        usage.modelPublicId(),
                        snapshot
                                ? usage.compactionOperationPublicId()
                                : operation.operationPublicId(),
                        snapshot
                                ? usage.compactionStatus()
                                : operation.status().name(),
                        snapshot || operation.trigger() == null
                                ? null : operation.trigger().name(),
                        usage,
                        now(),
                        operation.retryable(),
                        operation.errorCode()));
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static boolean terminal(AiConversationContextEvent event) {
        if ("timeout".equals(event.name())) {
            return true;
        }
        if (!"compaction_completed".equals(event.name())
                && !"compaction_failed".equals(event.name())) {
            return false;
        }
        // 旧任务终态与新版本 queued 紧邻时，以 Redis 中当前活动状态为准，避免提前关闭后漏掉后续任务。
        String currentStatus = event.data().compactionStatus();
        return !AiConversationCompactionStatus.QUEUED.name().equals(currentStatus)
                && !AiConversationCompactionStatus.RUNNING.name().equals(
                        currentStatus);
    }

    private static void closeQuietly(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignoredFailure) {
            // 本机监听器清理失败不能改变 Redis 快照或压缩任务。
        }
    }
}
