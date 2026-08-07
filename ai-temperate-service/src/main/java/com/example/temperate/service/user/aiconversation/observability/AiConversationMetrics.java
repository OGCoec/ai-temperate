package com.example.temperate.service.user.aiconversation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 记录 AI 会话请求、缓存、并发、压缩和计费的固定低基数指标。
 *
 * <p>所有标签值都由本类白名单生成，禁止传入用户、会话、模型、Redis Key 或异常消息。</p>
 */
@Component
public final class AiConversationMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeContextEventConnections =
            new AtomicInteger();

    public AiConversationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        Gauge.builder(
                        "ai.conversation.context.events.active",
                        activeContextEventConnections,
                        AtomicInteger::get)
                .register(registry);
    }

    public void request(String outcome) {
        counter("ai.conversation.request", "outcome", requestOutcome(outcome));
    }

    public void concurrency(String outcome) {
        counter("ai.conversation.concurrency", "outcome", concurrencyOutcome(outcome));
    }

    public void context(String outcome) {
        counter("ai.conversation.context", "outcome", contextOutcome(outcome));
    }

    public void compaction(String layer, String outcome) {
        Counter.builder("ai.conversation.compaction")
                .tag("layer", "ephemeral".equals(layer)
                        ? "ephemeral" : "persistent")
                .tag("outcome", operationOutcome(outcome))
                .register(registry)
                .increment();
    }

    public void contextCompactionQueued(String trigger) {
        counter(
                "ai.conversation.context.compaction.queued",
                "trigger",
                compactionTrigger(trigger));
    }

    public void contextCompactionDuration(Duration elapsed, String outcome) {
        Timer.builder("ai.conversation.context.compaction.duration")
                .tag("outcome", contextCompactionOutcome(outcome))
                .register(registry)
                .record(elapsed);
    }

    public void contextHardLimitWait(Duration elapsed, String outcome) {
        Timer.builder("ai.conversation.context.hard_limit.wait.duration")
                .tag("outcome", hardLimitWaitOutcome(outcome))
                .register(registry)
                .record(elapsed);
    }

    public void contextEventsOpened() {
        activeContextEventConnections.incrementAndGet();
        counter("ai.conversation.context.events.opened", "outcome", "success");
    }

    public void contextEventsClosed() {
        activeContextEventConnections.updateAndGet(current ->
                Math.max(0, current - 1));
        counter("ai.conversation.context.events.closed", "outcome", "success");
    }

    public void billing(String operation, String outcome) {
        billing(operation, outcome, 1D);
    }

    public void billing(String operation, String outcome, double amount) {
        Counter.builder("ai.conversation.billing")
                .tag("operation", billingOperation(operation))
                .tag("outcome", operationOutcome(outcome))
                .register(registry)
                .increment(Math.max(0D, amount));
    }

    public void firstByte(Duration elapsed) {
        Timer.builder("ai.conversation.stream.first_byte")
                .register(registry)
                .record(elapsed);
    }

    public void stream(Duration elapsed) {
        Timer.builder("ai.conversation.stream.duration")
                .register(registry)
                .record(elapsed);
    }

    public void redisBatch(int bytes, String outcome) {
        DistributionSummary.builder("ai.conversation.redis.batch.bytes")
                .tag("outcome", operationOutcome(outcome))
                .register(registry)
                .record(Math.max(0, bytes));
    }

    public void generationQueued() {
        counter("ai.conversation.generation.queued", "outcome", "success");
    }

    public void generationStarted() {
        counter("ai.conversation.generation.started", "outcome", "success");
    }

    public void observerAttached() {
        counter("ai.conversation.observer.attached", "outcome", "success");
    }

    public void observerDetached() {
        counter("ai.conversation.observer.detached", "outcome", "success");
    }

    public void detachCheck(String outcome) {
        String metric = "expired".equals(outcome)
                ? "ai.conversation.detach.check.expired"
                : "ai.conversation.detach.check.stale";
        counter(metric, "outcome", "success");
    }

    public void generationCancelRequested() {
        counter("ai.conversation.cancel.requested", "outcome", "success");
    }

    public void terminalPublished() {
        counter("ai.conversation.terminal.published", "outcome", "success");
    }

    public void generationBilling(Duration elapsed, String outcome) {
        Timer.builder("ai.conversation.billing.duration")
                .tag("outcome", operationOutcome(outcome))
                .register(registry)
                .record(elapsed);
    }

    public void rabbitConfirmFailure() {
        counter("ai.conversation.rabbit.confirm.failures", "outcome", "failed");
    }

    public void rabbitDeadLetter() {
        counter("ai.conversation.rabbit.dead.letters", "outcome", "failed");
    }

    public void generationReconcileRequired() {
        counter("ai.conversation.reconcile.required", "outcome", "failed");
    }

    public void imagePreview(String phase) {
        counter(
                "ai.conversation.image.preview",
                "phase",
                "FINAL".equals(phase) ? "final" : "partial");
    }

    public void imagePersistence(Duration elapsed, String outcome) {
        Timer.builder("ai.conversation.image.persistence.duration")
                .tag("outcome", switch (outcome) {
                    case "success", "dropped" -> outcome;
                    default -> "failed";
                })
                .register(registry)
                .record(elapsed);
    }

    private void counter(String name, String key, String value) {
        Counter.builder(name)
                .tag(key, value)
                .register(registry)
                .increment();
    }

    private static String requestOutcome(String value) {
        return switch (value) {
            case "settled", "interrupted", "reconcile", "failed" -> value;
            default -> "failed";
        };
    }

    private static String concurrencyOutcome(String value) {
        return switch (value) {
            case "acquired", "global_rejected", "user_rejected",
                    "unavailable", "renew_failed" -> value;
            default -> "unavailable";
        };
    }

    private static String contextOutcome(String value) {
        return switch (value) {
            case "hit", "miss", "rebuild", "damaged",
                    "generation_conflict", "unavailable" -> value;
            default -> "unavailable";
        };
    }

    private static String operationOutcome(String value) {
        return switch (value) {
            case "success", "failed", "skipped", "conflict" -> value;
            default -> "failed";
        };
    }

    private static String compactionTrigger(String value) {
        return switch (value) {
            case "MODEL_SWITCH", "ANSWER_COMPLETED", "USER_STOP",
                    "HASH_FIELD_SAFETY", "HARD_LIMIT_WAIT" -> value;
            default -> "ANSWER_COMPLETED";
        };
    }

    private static String contextCompactionOutcome(String value) {
        return switch (value) {
            case "success", "failed", "rejected" -> value;
            default -> "failed";
        };
    }

    private static String hardLimitWaitOutcome(String value) {
        return switch (value) {
            case "success", "failed", "timeout", "cancelled" -> value;
            default -> "failed";
        };
    }

    private static String billingOperation(String value) {
        return switch (value) {
            case "reserve", "refund", "supplement", "reconcile" -> value;
            default -> "reconcile";
        };
    }
}
