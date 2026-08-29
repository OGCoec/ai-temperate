package com.example.temperate.service.user.membership.payment.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该组件是来集中注册会员支付闭环的低基数计数器、耗时Timer和队列大小Gauge，禁止添加订单、回调、流水或用户标签。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter callbackReceived;
    private final Counter callbackDuplicate;
    private final Counter callbackRejected;
    private final Counter callbackRecovered;
    private final Counter orderPersisted;
    private final Counter orderPersistFailure;
    private final Counter paymentCheck;
    private final Counter paymentQuery;
    private final Counter closing;
    private final Counter latePaid;
    private final Counter refundRequired;
    private final Counter dlq;
    private final DistributionSummary redisWriteBatchSize;
    private final AtomicLong callbackProcessingSize = new AtomicLong();
    private final AtomicLong orderDirtySize = new AtomicLong();
    private final AtomicLong orderProcessingSize = new AtomicLong();
    private final AtomicInteger redisWriteInflight = new AtomicInteger();
    private final AtomicInteger rabbitPublishInflight = new AtomicInteger();
    private final EnumMap<MembershipPaymentOperation, AtomicInteger> operationInflight =
            new EnumMap<>(MembershipPaymentOperation.class);
    private final EnumMap<MembershipPaymentWorker, AtomicReference<WorkerRunSnapshot>>
            workerRuns = new EnumMap<>(MembershipPaymentWorker.class);

    public MembershipPaymentMetrics(MeterRegistry meterRegistry) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.meterRegistry = registry;
        callbackReceived = registry.counter("membership_payment_callback_received_total");
        callbackDuplicate = registry.counter("membership_payment_callback_duplicate_total");
        callbackRejected = registry.counter("membership_payment_callback_rejected_total");
        callbackRecovered = registry.counter("membership_payment_callback_recovered_total");
        orderPersisted = registry.counter("membership_order_persisted_total");
        orderPersistFailure = registry.counter("membership_order_persist_failure_total");
        paymentCheck = registry.counter("membership_payment_check_total");
        paymentQuery = registry.counter("membership_payment_query_total");
        closing = registry.counter("membership_payment_closing_total");
        latePaid = registry.counter("membership_payment_late_paid_total");
        refundRequired = registry.counter("membership_payment_refund_required_total");
        dlq = registry.counter("membership_payment_dlq_total");
        redisWriteBatchSize = DistributionSummary.builder(
                        "membership_payment_redis_write_batch_size")
                .baseUnit("orders")
                .register(registry);
        Gauge.builder(
                        "membership_payment_callback_processing_size",
                        callbackProcessingSize,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_order_dirty_size",
                        orderDirtySize,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_order_processing_size",
                        orderProcessingSize,
                        AtomicLong::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_payment_redis_write_inflight",
                        redisWriteInflight,
                        AtomicInteger::doubleValue)
                .register(registry);
        Gauge.builder(
                        "membership_payment_rabbit_publish_unconfirmed",
                        rabbitPublishInflight,
                        AtomicInteger::doubleValue)
                .register(registry);
        for (MembershipPaymentOperation operation : MembershipPaymentOperation.values()) {
            AtomicInteger inflight = new AtomicInteger();
            operationInflight.put(operation, inflight);
            Gauge.builder(
                            "membership_payment_operation_inflight",
                            inflight,
                            AtomicInteger::doubleValue)
                    .tag("operation", tag(operation))
                    .register(registry);
        }
        for (MembershipPaymentWorker worker : MembershipPaymentWorker.values()) {
            workerRuns.put(
                    worker,
                    new AtomicReference<>(WorkerRunSnapshot.empty(worker)));
        }
    }

    public void callbackReceived(boolean duplicate) {
        callbackReceived.increment();
        if (duplicate) {
            callbackDuplicate.increment();
        }
    }

    public void callbackRejected() {
        callbackRejected.increment();
    }

    public void callbackRejected(int count) {
        if (count > 0) {
            callbackRejected.increment(count);
        }
    }

    public void callbackRecovered(int count) {
        if (count > 0) {
            callbackRecovered.increment(count);
        }
    }

    public void callbackProcessingSize(long size) {
        callbackProcessingSize.set(Math.max(0L, size));
    }

    public void orderQueueSizes(long dirty, long processing) {
        orderDirtySize.set(Math.max(0L, dirty));
        orderProcessingSize.set(Math.max(0L, processing));
    }

    public void orderPersisted(int count) {
        if (count > 0) {
            orderPersisted.increment(count);
        }
    }

    public void orderPersistFailure() {
        orderPersistFailure.increment();
    }

    public void paymentCheck() {
        paymentCheck.increment();
    }

    public void paymentQuery() {
        paymentQuery.increment();
    }

    public void closing() {
        closing.increment();
    }

    public void latePaid() {
        latePaid.increment();
    }

    public void refundRequired() {
        refundRequired.increment();
    }

    public void dlq() {
        dlq.increment();
    }

    public void redisWriteQueueWait(String outcome, long elapsedNanos) {
        Timer.builder("membership_payment_redis_write_queue_wait")
                .tag("outcome", safeTag(outcome))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void redisWriteBatchSize(int size) {
        if (size > 0) {
            redisWriteBatchSize.record(size);
        }
    }

    public void redisWriteBreakdown(
            String phase,
            String outcome,
            int lane,
            long elapsedNanos) {
        String metricPhase = switch (phase) {
            case "permit", "queue", "batch", "execution", "dispatch" -> phase;
            default -> "unavailable";
        };
        Timer.builder("membership_payment_redis_write_" + metricPhase)
                .tags(
                        "outcome", safeTag(outcome),
                        "lane", lane == 0 || lane == 1 ? Integer.toString(lane) : "unavailable")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void redisWriteInflightChanged(int delta) {
        redisWriteInflight.updateAndGet(value -> Math.max(0, value + delta));
    }

    public void redisWriteRejected(String outcome) {
        meterRegistry.counter(
                        "membership_payment_redis_write_rejected_total",
                        "outcome", safeTag(outcome))
                .increment();
    }

    public void rabbitPublishPhase(String phase, String outcome, long elapsedNanos) {
        String metricPhase = switch (phase) {
            case "permit", "queue", "send", "confirm" -> phase;
            default -> "unavailable";
        };
        Timer.builder("membership_payment_rabbit_publish_" + metricPhase)
                .tag("outcome", safeTag(outcome))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void rabbitPublishInflightChanged(int delta) {
        rabbitPublishInflight.updateAndGet(value -> Math.max(0, value + delta));
    }

    public void rabbitPublishRejected(String outcome) {
        meterRegistry.counter(
                        "membership_payment_rabbit_publish_rejected_total",
                        "outcome", safeTag(outcome))
                .increment();
    }

    public void rabbitPublishNack() {
        meterRegistry.counter("membership_payment_rabbit_publish_nack_total").increment();
    }

    public void rabbitPublishReturned() {
        meterRegistry.counter("membership_payment_rabbit_publish_return_total").increment();
    }

    public void databaseTransaction(
            MembershipPaymentOperation operation,
            boolean succeeded,
            long elapsedNanos) {
        Timer.builder("membership_payment_database_transaction")
                .tags(
                        "operation", tag(Objects.requireNonNull(operation)),
                        "outcome", succeeded ? "success" : "failed")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void operationStarted(MembershipPaymentOperation operation) {
        operationInflight.get(Objects.requireNonNull(operation)).incrementAndGet();
    }

    public void operationCompleted(
            MembershipPaymentOperation operation,
            String outcome,
            long elapsedNanos) {
        MembershipPaymentOperation validOperation = Objects.requireNonNull(operation);
        operationInflight.get(validOperation).updateAndGet(value -> Math.max(0, value - 1));
        String operationTag = tag(validOperation);
        String outcomeTag = safeTag(outcome);
        meterRegistry.counter(
                        "membership_payment_operation_total",
                        "operation", operationTag,
                        "outcome", outcomeTag)
                .increment();
        Timer.builder("membership_payment_operation_duration")
                .tags("operation", operationTag, "outcome", outcomeTag)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void stepCompleted(
            MembershipPaymentTimingStep step,
            boolean succeeded,
            long elapsedNanos) {
        Timer.builder("membership_payment_step_duration")
                .tags(
                        "step", tag(Objects.requireNonNull(step)),
                        "outcome", succeeded ? "success" : "failed")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void rabbitDeliveryOverdue(
            String flow,
            int stageIndex,
            long overdueMillis) {
        if (overdueMillis < 0L) {
            return;
        }
        String flowTag = safeTag(flow);
        Timer.builder("membership_payment_rabbit_delivery_overdue")
                .tags(
                        "flow", flowTag,
                        "stage", safeStage(flowTag, stageIndex))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(overdueMillis, TimeUnit.MILLISECONDS);
    }

    public void transition(String transition, String outcome) {
        if (MembershipPaymentTimingContext.NONE.equals(transition)) {
            return;
        }
        meterRegistry.counter(
                        "membership_payment_transition_total",
                        "transition", safeTag(transition),
                        "outcome", safeTag(outcome))
                .increment();
    }

    /**
     * 记录一个自然调度轮次的有界处理事实；线程名只进入内存快照，不作为指标标签，避免高基数污染。
     */
    public void workerRunCompleted(
            MembershipPaymentWorker worker,
            int batches,
            int claimedItems,
            String outcome,
            long elapsedNanos,
            String threadName) {
        MembershipPaymentWorker validWorker = Objects.requireNonNull(worker);
        int validBatches = Math.max(0, batches);
        int validItems = Math.max(0, claimedItems);
        long validElapsedNanos = Math.max(0L, elapsedNanos);
        String validOutcome = safeTag(outcome);
        String validThreadName = safeThreadName(threadName);
        long completedAt = System.currentTimeMillis();
        workerRuns.get(validWorker).updateAndGet(previous -> new WorkerRunSnapshot(
                validWorker,
                previous.runCount() + 1L,
                validBatches,
                validItems,
                Math.max(previous.maximumBatches(), validBatches),
                Math.max(previous.maximumClaimedItems(), validItems),
                validOutcome,
                validElapsedNanos,
                validThreadName,
                completedAt));
        String workerTag = tag(validWorker);
        meterRegistry.counter(
                        "membership_payment_worker_run_total",
                        "worker", workerTag,
                        "outcome", validOutcome)
                .increment();
        DistributionSummary.builder("membership_payment_worker_run_batches")
                .tag("worker", workerTag)
                .register(meterRegistry)
                .record(validBatches);
        DistributionSummary.builder("membership_payment_worker_run_claimed_items")
                .tag("worker", workerTag)
                .baseUnit("items")
                .register(meterRegistry)
                .record(validItems);
        Timer.builder("membership_payment_worker_run_duration")
                .tags("worker", workerTag, "outcome", validOutcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(validElapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 返回指定 Worker 的不可变最新快照；调用方只能读取，不能重置正式运行证据。
     */
    public WorkerRunSnapshot workerSnapshot(MembershipPaymentWorker worker) {
        return workerRuns.get(Objects.requireNonNull(worker)).get();
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeTag(String value) {
        String normalized = Objects.requireNonNullElse(value, "unavailable")
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("^[a-z0-9_]{1,64}$")
                ? normalized
                : "unavailable";
    }

    private static String safeStage(String flow, int stageIndex) {
        // Rabbit消息属于不可信边界；指标只接受状态机固定阶段，避免伪造索引制造高基数时序。
        boolean valid = ("pending".equals(flow) && stageIndex >= 0 && stageIndex <= 8)
                || ("closing".equals(flow) && stageIndex >= 0 && stageIndex <= 4);
        return valid ? Integer.toString(stageIndex) : "unavailable";
    }

    private static String safeThreadName(String value) {
        String normalized = Objects.requireNonNullElse(value, "unavailable");
        return normalized.matches("^[A-Za-z0-9._-]{1,96}$")
                ? normalized
                : "unavailable";
    }

    /** 该快照承载一个 Worker 的最新运行事实和进程启动后最大单轮处理证据。 */
    public record WorkerRunSnapshot(
            MembershipPaymentWorker worker,
            long runCount,
            int lastBatches,
            int lastClaimedItems,
            int maximumBatches,
            int maximumClaimedItems,
            String lastOutcome,
            long lastDurationNanos,
            String lastThreadName,
            long lastCompletedAtEpochMillis) {

        private static WorkerRunSnapshot empty(MembershipPaymentWorker worker) {
            return new WorkerRunSnapshot(
                    worker,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    "unavailable",
                    0L,
                    "unavailable",
                    0L);
        }
    }
}
