package com.example.temperate.service.user.aiconversation.compaction.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionClaim;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionOperation;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionRequestResult;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionService;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStateStore;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStatus;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.config.AiConversationContextUsageProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventNotification;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventSubscriber;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 在请求线程只完成权威阈值复核和 Redis 单飞声明，并把实际模型压缩提交到有界执行器。
 *
 * <p>硬容量等待只监听跨实例事件并设置固定超时，不轮询 Redis，也不会回退到同步模型调用。</p>
 */
@Service
public final class AiConversationCompactionCoordinatorImpl
        implements AiConversationCompactionCoordinator {

    private final AiConversationContextUsageService usageService;
    private final AiConversationContextService contextService;
    private final AiConversationCompactionStateStore stateStore;
    private final AiConversationContextEventSubscriber eventSubscriber;
    private final AiConversationCompactionService compactionService;
    private final AiConversationContextUsageProperties properties;
    private final AiConversationProperties conversationProperties;
    private final PublicIdCodec publicIdCodec;
    private final Executor executor;
    private final AiConversationMetrics metrics;

    public AiConversationCompactionCoordinatorImpl(
            AiConversationContextUsageService usageService,
            AiConversationContextService contextService,
            AiConversationCompactionStateStore stateStore,
            AiConversationContextEventSubscriber eventSubscriber,
            AiConversationCompactionService compactionService,
            AiConversationContextUsageProperties properties,
            AiConversationProperties conversationProperties,
            PublicIdCodec publicIdCodec,
            @Qualifier("aiConversationCompactionExecutor") Executor executor,
            AiConversationMetrics metrics) {
        this.usageService = Objects.requireNonNull(usageService);
        this.contextService = Objects.requireNonNull(contextService);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.eventSubscriber = Objects.requireNonNull(eventSubscriber);
        this.compactionService = Objects.requireNonNull(compactionService);
        this.properties = Objects.requireNonNull(properties);
        this.conversationProperties = Objects.requireNonNull(
                conversationProperties);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.executor = Objects.requireNonNull(executor);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public AiConversationCompactionRequestResult requestOwned(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId,
            UUID idempotencyKey,
            AiConversationCompactionTrigger trigger) {
        Objects.requireNonNull(idempotencyKey);
        AiConversationContextUsage usage = usageService.getOwned(
                userId,
                conversationId,
                conversationPublicId,
                modelPublicId);
        return requestEvaluated(
                conversationId,
                conversationPublicId,
                usage,
                publicIdCodec.decode(modelPublicId),
                trigger);
    }

    @Override
    public AiConversationCompactionRequestResult request(
            byte[] conversationId,
            String conversationPublicId,
            long modelId,
            AiConversationCompactionTrigger trigger) {
        AiConversationContextUsage usage = usageService.get(
                conversationId,
                conversationPublicId,
                modelId);
        return requestEvaluated(
                conversationId,
                conversationPublicId,
                usage,
                modelId,
                trigger);
    }

    private AiConversationCompactionRequestResult requestEvaluated(
            byte[] conversationId,
            String conversationPublicId,
            AiConversationContextUsage usage,
            long modelId,
            AiConversationCompactionTrigger trigger) {
        boolean hashFieldSafetyReached = contextService.load(
                        conversationId, conversationPublicId)
                .fieldCount()
                >= conversationProperties.compactionHashFieldThreshold();
        if (!usage.thresholdReached()
                && !hashFieldSafetyReached
                && trigger != AiConversationCompactionTrigger.HARD_LIMIT_WAIT) {
            publishUsageBestEffort(conversationPublicId, usage.contextRevision());
            return new AiConversationCompactionRequestResult(
                    "NOT_REQUIRED", null, usage);
        }
        AiConversationCompactionClaim claim = stateStore.claim(
                conversationPublicId,
                usage.contextRevision(),
                trigger == AiConversationCompactionTrigger.HARD_LIMIT_WAIT
                        ? trigger
                        : hashFieldSafetyReached
                                ? AiConversationCompactionTrigger.HASH_FIELD_SAFETY
                                : trigger);
        if (claim.created()) {
            metrics.contextCompactionQueued(claim.operation().trigger().name());
            submit(
                    conversationId.clone(),
                    conversationPublicId,
                    modelId,
                    claim.operation());
        } else {
            publishUsageBestEffort(conversationPublicId, usage.contextRevision());
        }
        return new AiConversationCompactionRequestResult(
                claim.created()
                        ? "QUEUED" : claim.operation().status().name(),
                claim.operation(),
                usage);
    }

    private void publishUsageBestEffort(
            String conversationPublicId,
            long contextRevision) {
        try {
            stateStore.publishUsage(conversationPublicId, contextRevision);
        } catch (RuntimeException ignoredPublishFailure) {
            // SSE 是派生通知；发布失败不能阻止权威快照查询、回答结算或压缩任务声明。
        }
    }

    private void submit(
            byte[] conversationId,
            String conversationPublicId,
            long modelId,
            AiConversationCompactionOperation operation) {
        try {
            executor.execute(() -> execute(
                    conversationId,
                    conversationPublicId,
                    modelId,
                    operation));
        } catch (RejectedExecutionException queueFull) {
            metrics.contextCompactionDuration(Duration.ZERO, "rejected");
            stateStore.markFailed(
                    conversationPublicId,
                    operation.operationPublicId(),
                    AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED.name(),
                    true);
        }
    }

    private void execute(
            byte[] conversationId,
            String conversationPublicId,
            long modelId,
            AiConversationCompactionOperation operation) {
        long startedAt = System.nanoTime();
        String outcome = "failed";
        try {
            stateStore.markRunning(
                    conversationPublicId, operation.operationPublicId());
            AiConversationContextSnapshot snapshot = contextService.load(
                    conversationId, conversationPublicId);
            // 任务只处理声明时已经存在的持久尾部；并发追加消息必须留给后续版本，防止旧任务越过安全截止点。
            long safeCutoffMessageId = snapshot.latestPersistedMessageId();
            long safeEphemeralOrdinal = latestIncludedEphemeralOrdinal(snapshot);
            boolean ephemeralCompacted = compactionService.compactEphemeral(
                    conversationPublicId, snapshot.generation());
            AiConversationContextSnapshot afterEphemeral = contextService.load(
                    conversationId, conversationPublicId);
            if (safeEphemeralOrdinal > 0L
                    && !ephemeralCompacted
                    && hasIncludedEphemeralAtOrBefore(
                            afterEphemeral, safeEphemeralOrdinal)) {
                throw compactionFailed();
            }
            if (safeCutoffMessageId > afterEphemeral.lastCompactedMessageId()) {
                boolean durableCompacted = compactionService.compactDurable(
                        conversationId,
                        conversationPublicId,
                        afterEphemeral.generation(),
                        safeCutoffMessageId);
                if (!durableCompacted
                        && contextService.load(
                                        conversationId, conversationPublicId)
                                .lastCompactedMessageId()
                                < safeCutoffMessageId) {
                    throw compactionFailed();
                }
            }
            AiConversationContextSnapshot completed = contextService.load(
                    conversationId, conversationPublicId);
            publishUsageBestEffort(
                    conversationPublicId, completed.contextRevision());
            stateStore.markCompleted(
                    conversationPublicId,
                    operation.operationPublicId(),
                    completed.contextRevision());
            outcome = "success";
            try {
                boolean frozenEphemeralRemainder =
                        hasIncludedEphemeralAtOrBefore(
                                completed, safeEphemeralOrdinal);
                if (frozenEphemeralRemainder
                        || completed.latestPersistedMessageId()
                                > safeCutoffMessageId
                        || latestIncludedEphemeralOrdinal(completed)
                                > safeEphemeralOrdinal) {
                    AiConversationContextUsage latestUsage = usageService.get(
                            conversationId,
                            conversationPublicId,
                            modelId);
                    requestEvaluated(
                            conversationId,
                            conversationPublicId,
                            latestUsage,
                            modelId,
                            operation.trigger()
                                            == AiConversationCompactionTrigger
                                                    .HARD_LIMIT_WAIT
                                    ? AiConversationCompactionTrigger
                                            .ANSWER_COMPLETED
                                    : operation.trigger());
                }
            } catch (RuntimeException ignoredFollowupFailure) {
                // 当前任务已经完成；新尾部的派生调度失败不得反向改写本次终态，下一次发送仍会重新检测。
            }
        } catch (RuntimeException failure) {
            try {
                stateStore.markFailed(
                        conversationPublicId,
                        operation.operationPublicId(),
                        AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED.name(),
                        true);
            } catch (RuntimeException ignoredStateFailure) {
                // 任务状态写入失败由短 TTL 收敛，不能在有界执行器中无限重试。
            }
        } finally {
            metrics.contextCompactionDuration(
                    elapsedSince(startedAt), outcome);
        }
    }

    private static long latestIncludedEphemeralOrdinal(
            AiConversationContextSnapshot snapshot) {
        return snapshot.turns().stream()
                .filter(turn -> turn.ephemeral() && turn.includedInPrompt())
                .map(turn -> turn.ordinal())
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
    }

    private static boolean hasIncludedEphemeralAtOrBefore(
            AiConversationContextSnapshot snapshot,
            long cutoffOrdinal) {
        return snapshot.turns().stream()
                .filter(turn -> turn.ephemeral() && turn.includedInPrompt())
                .map(turn -> turn.ordinal())
                .filter(Objects::nonNull)
                .anyMatch(ordinal -> ordinal <= cutoffOrdinal);
    }

    private static AiConversationException compactionFailed() {
        return new AiConversationException(
                AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED,
                "会话上下文压缩未能提交。",
                true);
    }

    @Override
    public Mono<AiConversationCompactionOperation> awaitTerminal(
            String conversationPublicId,
            String operationPublicId) {
        Objects.requireNonNull(operationPublicId);
        return Mono.defer(() -> {
            long startedAt = System.nanoTime();
            AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
            Mono<AiConversationCompactionOperation> terminal = Mono.create(sink -> {
                try {
                    subscription.set(eventSubscriber.subscribe(
                            conversationPublicId,
                            event -> completeFromNotification(
                                    conversationPublicId, event, sink)));
                    completeIfTerminal(
                            conversationPublicId, sink);
                } catch (RuntimeException failure) {
                    sink.error(failure);
                }
            });
            return terminal
                    .timeout(
                            properties.hardLimitWaitTimeout(),
                            Mono.error(new AiConversationException(
                                    AiConversationErrorCode
                                            .AI_CONTEXT_COMPACTION_TIMEOUT,
                                    "等待会话上下文压缩超时",
                                    true)))
                    .doOnSuccess(ignored -> metrics.contextHardLimitWait(
                            elapsedSince(startedAt), "success"))
                    .doOnError(failure -> metrics.contextHardLimitWait(
                            elapsedSince(startedAt), waitOutcome(failure)))
                    .doFinally(ignored -> closeQuietly(subscription.get()));
        });
    }

    private void completeIfTerminal(
            String conversationPublicId,
            reactor.core.publisher.MonoSink<AiConversationCompactionOperation> sink) {
        AiConversationCompactionOperation current = stateStore
                .find(conversationPublicId)
                .orElse(null);
        if (current == null || current.status().active()) {
            return;
        }
        if (current.status() == AiConversationCompactionStatus.IDLE) {
            // completed 后的较新用量事件会把过期终态折叠为 IDLE；对已经拿到任务 ID 的等待方等价于成功完成。
            sink.success(current);
            return;
        }
        if (current.status() == AiConversationCompactionStatus.FAILED) {
            sink.error(new AiConversationException(
                    AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED,
                    "会话上下文压缩失败",
                    current.retryable()));
            return;
        }
        sink.success(current);
    }

    private void completeFromNotification(
            String conversationPublicId,
            AiConversationContextEventNotification notification,
            reactor.core.publisher.MonoSink<AiConversationCompactionOperation> sink) {
        if ("compaction_completed".equals(notification.eventType())) {
            sink.success(stateStore.find(conversationPublicId)
                    .orElse(AiConversationCompactionOperation.idle(
                            notification.eventRevision())));
            return;
        }
        if ("compaction_failed".equals(notification.eventType())) {
            AiConversationCompactionOperation current = stateStore
                    .find(conversationPublicId)
                    .orElse(null);
            if (current != null
                    && current.status().active()
                    && current.eventRevision() > notification.eventRevision()) {
                return;
            }
            sink.error(new AiConversationException(
                    AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED,
                    "会话上下文压缩失败",
                    current == null || current.retryable()));
            return;
        }
        completeIfTerminal(conversationPublicId, sink);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignoredFailure) {
            // 本地监听器清理失败不能改变 Redis 中的任务终态。
        }
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static String waitOutcome(Throwable failure) {
        if (failure instanceof AiConversationException conversationFailure
                && conversationFailure.code()
                == AiConversationErrorCode.AI_CONTEXT_COMPACTION_TIMEOUT) {
            return "timeout";
        }
        return "failed";
    }
}
