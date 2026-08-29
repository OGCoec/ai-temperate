package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentRedisWriteProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRedisWriteBreakdown;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingRecorder;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteMode;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteRuntimeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisPipelineException;
import org.springframework.stereotype.Component;

/**
 * 该实现是来用最多六条固定 lane 合并会员订单 Redis 写入，在总在途 384 的边界内提高 Pipeline 利用率。
 *
 * <p>同一订单永远按哈希进入同一单线程 lane，因此支付增量更新与完整恢复保持 FIFO；生产默认每条 lane 同时只执行
 * 一个最多 64 条的 Pipeline，所以六条 lane 最多存在六个 Pipeline 批次。各 lane 取得独立的 Spring
 * RedisConnection 包装并发提交，绝不共享会话级连接对象。Pipeline 不承担批次事务，失败依靠版本裁决重试。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderSnapshotWriteCoordinatorImpl
        implements MembershipOrderSnapshotWriteCoordinator, InitializingBean, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MembershipOrderSnapshotWriteCoordinatorImpl.class);
    private static final long IDLE_POLL_MILLIS = 100L;
    private static final int FULL_RESTORE_BATCHES_PER_PAYMENT_BATCH = 4;

    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipPaymentRedisWriteProperties properties;
    private final MembershipPaymentMetrics metrics;
    private final MembershipPaymentTimingRecorder timingRecorder;
    private final ExecutorService executor;
    private final int laneCount;
    private final Semaphore permits;
    private final List<LaneQueues> laneQueues;
    private final List<Semaphore> laneSignals;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> fullRestoreBarriers =
            new ConcurrentHashMap<>();
    private final Set<PendingWrite> outstanding = ConcurrentHashMap.newKeySet();
    private final Object submissionMonitor = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean forceStopping = new AtomicBoolean();
    private final CountDownLatch stopped;
    private final List<Future<?>> workerFutures;

    public MembershipOrderSnapshotWriteCoordinatorImpl(
            MembershipOrderSnapshotStore snapshotStore,
            MembershipPaymentRedisWriteProperties properties,
            MembershipPaymentMetrics metrics,
            MembershipPaymentTimingRecorder timingRecorder,
            @Qualifier("membershipPaymentRedisWriteExecutor") ExecutorService executor) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.timingRecorder = Objects.requireNonNull(timingRecorder);
        this.executor = Objects.requireNonNull(executor);
        this.laneCount = properties.laneCount();
        this.permits = new Semaphore(properties.maximumInflight(), true);
        this.laneQueues = java.util.stream.IntStream.range(0, laneCount)
                .mapToObj(ignored -> new LaneQueues(
                        new ArrayBlockingQueue<>(properties.maximumInflight(), true),
                        new ArrayBlockingQueue<>(properties.maximumInflight(), true)))
                .toList();
        this.laneSignals = java.util.stream.IntStream.range(0, laneCount)
                .mapToObj(ignored -> new Semaphore(0))
                .toList();
        this.stopped = new CountDownLatch(laneCount);
        this.workerFutures = new ArrayList<>(laneCount);
    }

    /** 配置的一至六条 worker 必须全部启动成功后才接收请求；部分启动会立即停止并使 Bean 创建失败。 */
    @Override
    public void afterPropertiesSet() {
        if (!accepting.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Membership payment Redis write coordinator was already started.");
        }
        try {
            for (int lane = 0; lane < laneCount; lane++) {
                int workerLane = lane;
                workerFutures.add(executor.submit(() -> drainLoop(workerLane)));
            }
        } catch (RuntimeException exception) {
            stopAll(unavailable("Membership order Redis lane failed to start.", exception));
            throw exception;
        }
    }

    @Override
    public MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot) {
        return submit(MembershipOrderSnapshotWriteMode.FULL_RESTORE, snapshot);
    }

    @Override
    public MembershipOrderSnapshot patchPaymentAttempt(
            MembershipOrderSnapshot databaseSnapshot) {
        return submit(MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH, databaseSnapshot);
    }

    /**
     * 只读取并发容器自身的瞬时计数；各字段可能跨纳秒变化，但始终足以裁决配置漂移和容量越界。
     */
    @Override
    public MembershipOrderSnapshotWriteRuntimeSnapshot runtimeSnapshot() {
        int availablePermits = permits.availablePermits();
        List<Integer> fullRestoreDepths = laneQueues.stream()
                .map(queues -> queues.fullRestore().size())
                .toList();
        List<Integer> paymentPatchDepths = laneQueues.stream()
                .map(queues -> queues.paymentAttemptPatch().size())
                .toList();
        List<Integer> totalDepths = java.util.stream.IntStream.range(0, laneCount)
                .map(index -> fullRestoreDepths.get(index) + paymentPatchDepths.get(index))
                .boxed()
                .toList();
        return new MembershipOrderSnapshotWriteRuntimeSnapshot(
                accepting.get(),
                properties.batchSize(),
                laneCount,
                properties.maximumInflight(),
                properties.maximumInflight() - availablePermits,
                availablePermits,
                fullRestoreDepths,
                paymentPatchDepths,
                totalDepths);
    }

    private MembershipOrderSnapshot submit(
            MembershipOrderSnapshotWriteMode mode,
            MembershipOrderSnapshot snapshot) {
        MembershipOrderSnapshot valid = Objects.requireNonNull(snapshot);
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + properties.submitTimeout().toNanos();
        if (!accepting.get()) {
            metrics.redisWriteRejected("not_accepting");
            throw unavailable("Membership order snapshot write coordinator is not accepting requests.");
        }

        CompletableFuture<Void> restoreBarrier = mode == MembershipOrderSnapshotWriteMode.FULL_RESTORE
                ? registerFullRestoreBarrier(valid.orderId())
                : null;
        if (mode == MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH) {
            awaitFullRestoreBarrier(valid.orderId(), deadlineNanos);
        }

        boolean acquired;
        try {
            acquired = permits.tryAcquire(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.redisWriteQueueWait("interrupted", System.nanoTime() - startedNanos);
            metrics.redisWriteRejected("interrupted");
            failRestoreBarrier(valid.orderId(), restoreBarrier, exception);
            throw unavailable("Membership order snapshot write was interrupted.", exception);
        }
        if (!acquired) {
            metrics.redisWriteQueueWait("timeout", System.nanoTime() - startedNanos);
            metrics.redisWriteRejected("permit_timeout");
            failRestoreBarrier(
                    valid.orderId(), restoreBarrier,
                    unavailable("Membership order snapshot write capacity timed out."));
            throw unavailable("Membership order snapshot write capacity timed out.");
        }
        metrics.redisWriteQueueWait("accepted", System.nanoTime() - startedNanos);

        // 同一订单始终按稳定摘要落到同一 lane；每条 lane 保持 FIFO，全部 lane 共享 384 个公平许可。
        int lane = Math.floorMod(valid.orderId().hashCode(), laneCount);
        long permitWaitNanos = Math.max(0L, System.nanoTime() - startedNanos);
        metrics.redisWriteBreakdown("permit", "accepted", lane, permitWaitNanos);
        PendingWrite pending;
        synchronized (submissionMonitor) {
            if (!accepting.get()) {
                permits.release();
                metrics.redisWriteRejected("stopping");
                failRestoreBarrier(
                        valid.orderId(), restoreBarrier,
                        unavailable("Membership order snapshot write coordinator is stopping."));
                throw unavailable("Membership order snapshot write coordinator is stopping.");
            }
            pending = new PendingWrite(
                    mode,
                    valid,
                    lane,
                    permitWaitNanos,
                    System.nanoTime(),
                    restoreBarrier,
                    new CompletableFuture<>());
            outstanding.add(pending);
            metrics.redisWriteInflightChanged(1);
            ArrayBlockingQueue<PendingWrite> queue = laneQueues.get(lane).queueFor(mode);
            if (!queue.offer(pending)) {
                fail(pending, unavailable("Membership order snapshot write lane is full."));
                metrics.redisWriteRejected("queue_full");
                throw unavailable("Membership order snapshot write lane is full.");
            }
            laneSignals.get(lane).release();
        }

        try {
            CoordinatedWriteResult result = pending.result().get(
                    remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            timingRecorder.recordRedisWriteBreakdown(result.breakdown());
            return result.snapshot();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.redisWriteRejected("result_interrupted");
            throw unavailable("Membership order snapshot write result was interrupted.", exception);
        } catch (TimeoutException exception) {
            metrics.redisWriteRejected("result_timeout");
            throw unavailable("Membership order snapshot write result timed out.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof MembershipPaymentInfrastructureException infrastructure) {
                throw infrastructure;
            }
            throw unavailable("Membership order snapshot write failed.", cause);
        }
    }

    private void drainLoop(int lane) {
        MembershipPaymentInfrastructureException terminalFailure = null;
        try {
            LaneQueues queues = laneQueues.get(lane);
            Semaphore signal = laneSignals.get(lane);
            int consecutiveFullRestoreBatches = 0;
            while (!forceStopping.get() && (accepting.get() || !queues.isEmpty())) {
                if (!signal.tryAcquire(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                SelectedWrite selected = selectNext(queues, consecutiveFullRestoreBatches);
                if (selected == null) {
                    continue;
                }
                long batchStartedNanos = System.nanoTime();
                processBatch(
                        collectBatch(selected.queue(), selected.first()),
                        batchStartedNanos);
                consecutiveFullRestoreBatches = selected.mode()
                        == MembershipOrderSnapshotWriteMode.FULL_RESTORE
                        ? Math.min(
                                FULL_RESTORE_BATCHES_PER_PAYMENT_BATCH,
                                consecutiveFullRestoreBatches + 1)
                        : 0;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!forceStopping.get()) {
                terminalFailure = unavailable(
                        "Membership order snapshot write lane was interrupted.", exception);
            }
        } catch (RuntimeException exception) {
            terminalFailure = unavailable(
                    "Membership order snapshot write lane stopped unexpectedly.", exception);
        } finally {
            if (terminalFailure != null) {
                LOGGER.error(
                        "Membership payment Redis write lane stopped unexpectedly; lane={}; exceptionClass={}",
                        lane,
                        terminalFailure.getClass().getSimpleName());
                stopAll(terminalFailure);
            }
            stopped.countDown();
        }
    }

    /** 两类队列同时有数据时严格执行四个创建批次后一个支付批次；单类为空时不制造空转。 */
    private SelectedWrite selectNext(
            LaneQueues queues,
            int consecutiveFullRestoreBatches) {
        boolean hasFullRestore = !queues.fullRestore().isEmpty();
        boolean hasPaymentPatch = !queues.paymentAttemptPatch().isEmpty();
        if (hasFullRestore && (!hasPaymentPatch
                || consecutiveFullRestoreBatches < FULL_RESTORE_BATCHES_PER_PAYMENT_BATCH)) {
            return new SelectedWrite(
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    queues.fullRestore(),
                    queues.fullRestore().poll());
        }
        if (hasPaymentPatch) {
            return new SelectedWrite(
                    MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                    queues.paymentAttemptPatch(),
                    queues.paymentAttemptPatch().poll());
        }
        if (hasFullRestore) {
            return new SelectedWrite(
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    queues.fullRestore(),
                    queues.fullRestore().poll());
        }
        return null;
    }

    private List<PendingWrite> collectBatch(
            ArrayBlockingQueue<PendingWrite> queue,
            PendingWrite first) throws InterruptedException {
        List<PendingWrite> batch = new ArrayList<>(properties.batchSize());
        batch.add(first);
        long flushDeadlineNanos = System.nanoTime() + properties.flushWindow().toNanos();
        while (batch.size() < properties.batchSize()) {
            long remaining = remainingNanos(flushDeadlineNanos);
            if (remaining == 0L) {
                break;
            }
            PendingWrite next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    private void processBatch(List<PendingWrite> batch, long batchStartedNanos) {
        metrics.redisWriteBatchSize(batch.size());
        long executionStartedNanos = System.nanoTime();
        try {
            List<MembershipOrderSnapshotWriteResult> firstResults = snapshotStore.writeAll(
                    batch.stream()
                            .map(pending -> new MembershipOrderSnapshotWriteCommand(
                                    pending.mode(), pending.snapshot()))
                            .toList());
            requireResultSize(firstResults, batch.size());

            List<Integer> restoreIndexes = new ArrayList<>();
            List<MembershipOrderSnapshotWriteCommand> restores = new ArrayList<>();
            for (int index = 0; index < firstResults.size(); index++) {
                MembershipOrderSnapshotWriteOutcome outcome = firstResults.get(index).outcome();
                if (outcome == MembershipOrderSnapshotWriteOutcome.MISSING
                        || outcome == MembershipOrderSnapshotWriteOutcome.REQUIRES_RESTORE) {
                    restoreIndexes.add(index);
                    restores.add(new MembershipOrderSnapshotWriteCommand(
                            MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                            batch.get(index).snapshot()));
                }
            }
            List<MembershipOrderSnapshotWriteResult> restoreResults = restores.isEmpty()
                    ? List.of()
                    : snapshotStore.writeAll(restores);
            requireResultSize(restoreResults, restores.size());

            List<MembershipOrderSnapshotWriteResult> resolved = new ArrayList<>(firstResults);
            for (int index = 0; index < restoreIndexes.size(); index++) {
                resolved.set(restoreIndexes.get(index), restoreResults.get(index));
            }
            long executionEndedNanos = System.nanoTime();
            for (int index = 0; index < batch.size(); index++) {
                MembershipOrderSnapshot result = resolved.get(index).snapshot();
                if (result == null
                        || !batch.get(index).snapshot().orderId().equals(result.orderId())) {
                    throw unavailable("Membership order snapshot write result is incomplete.");
                }
                PendingWrite pending = batch.get(index);
                long queueNanos = Math.max(
                        0L, batchStartedNanos - pending.enqueuedNanos());
                long batchNanos = Math.max(
                        0L, executionStartedNanos
                                - Math.max(batchStartedNanos, pending.enqueuedNanos()));
                long executionNanos = Math.max(
                        0L, executionEndedNanos - executionStartedNanos);
                long dispatchNanos = Math.max(0L, System.nanoTime() - executionEndedNanos);
                MembershipPaymentRedisWriteBreakdown breakdown =
                        new MembershipPaymentRedisWriteBreakdown(
                                pending.permitWaitNanos(),
                                queueNanos,
                                batchNanos,
                                executionNanos,
                                dispatchNanos,
                                batch.size(),
                                pending.lane());
                recordBreakdownMetrics(breakdown);
                complete(pending, new CoordinatedWriteResult(result, breakdown));
            }
        } catch (RuntimeException exception) {
            MembershipPaymentInfrastructureException failure =
                    exception instanceof MembershipPaymentInfrastructureException infrastructure
                            ? infrastructure
                            : unavailable("Membership order snapshot write pipeline failed.", exception);
            String failureCategory = classifyBatchFailure(exception);
            Throwable rootFailure = rootFailure(exception);
            // 批次异常只记录固定类别、lane、模式和异常类型，禁止把底层消息中的订单 ID 或 Redis Key 写入日志。
            LOGGER.error(
                    "event=membership_payment_redis_write_batch_failed lane={} mode={} batchSize={}"
                            + " category={} exceptionClass={} rootExceptionClass={}",
                    batch.getFirst().lane(),
                    batch.getFirst().mode(),
                    batch.size(),
                    failureCategory,
                    exception.getClass().getSimpleName(),
                    rootFailure.getClass().getSimpleName());
            metrics.redisWriteRejected("batch_" + failureCategory);
            for (PendingWrite pending : batch) {
                fail(pending, failure);
            }
        }
    }

    private static String classifyBatchFailure(Throwable failure) {
        boolean pipelineFailure = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String type = current.getClass().getSimpleName();
            if (current instanceof RedisConnectionFailureException
                    || type.contains("RedisConnection")) {
                return "connection";
            }
            if (current instanceof QueryTimeoutException || type.contains("Timeout")) {
                return "timeout";
            }
            if (current instanceof RedisPipelineException) {
                pipelineFailure = true;
            }
            if (current instanceof IllegalArgumentException) {
                return "invalid_result";
            }
        }
        return pipelineFailure ? "pipeline" : "unexpected";
    }

    private static Throwable rootFailure(Throwable failure) {
        Throwable root = Objects.requireNonNull(failure);
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static void requireResultSize(List<?> results, int expected) {
        if (results.size() != expected) {
            throw unavailable("Membership order snapshot write pipeline result is incomplete.");
        }
    }

    private void complete(PendingWrite pending, CoordinatedWriteResult result) {
        completeRestoreBarrier(pending);
        if (pending.result().complete(Objects.requireNonNull(result))) {
            finish(pending);
        }
    }

    private void recordBreakdownMetrics(MembershipPaymentRedisWriteBreakdown breakdown) {
        metrics.redisWriteBreakdown(
                "queue", "success", breakdown.lane(), breakdown.queueWaitNanos());
        metrics.redisWriteBreakdown(
                "batch", "success", breakdown.lane(), breakdown.batchWaitNanos());
        metrics.redisWriteBreakdown(
                "execution", "success", breakdown.lane(), breakdown.executionNanos());
        metrics.redisWriteBreakdown(
                "dispatch", "success", breakdown.lane(), breakdown.dispatchNanos());
    }

    private void fail(PendingWrite pending, MembershipPaymentInfrastructureException failure) {
        failRestoreBarrier(pending.snapshot().orderId(), pending.restoreBarrier(), failure);
        if (pending.result().completeExceptionally(Objects.requireNonNull(failure))) {
            finish(pending);
        }
    }

    private void finish(PendingWrite pending) {
        if (outstanding.remove(pending)) {
            permits.release();
            metrics.redisWriteInflightChanged(-1);
        }
    }

    private void stopAll(MembershipPaymentInfrastructureException failure) {
        synchronized (submissionMonitor) {
            accepting.set(false);
            forceStopping.set(true);
        }
        for (Future<?> worker : List.copyOf(workerFutures)) {
            worker.cancel(true);
        }
        for (PendingWrite pending : List.copyOf(outstanding)) {
            fail(pending, failure);
        }
        laneQueues.forEach(LaneQueues::clear);
    }

    /** 正常关闭先停止接收并等待两个 lane 排空；超时后统一失败所有未完成请求，禁止临时改道。 */
    @Override
    public void destroy() {
        synchronized (submissionMonitor) {
            accepting.set(false);
        }
        boolean interrupted = false;
        try {
            if (!stopped.await(properties.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                stopAll(unavailable("Membership order snapshot write shutdown timed out."));
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            stopAll(unavailable(
                    "Membership order snapshot write shutdown was interrupted.", exception));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static MembershipPaymentInfrastructureException unavailable(String message) {
        return new MembershipPaymentInfrastructureException(message);
    }

    private static MembershipPaymentInfrastructureException unavailable(
            String message,
            Throwable cause) {
        return new MembershipPaymentInfrastructureException(message, cause);
    }

    private CompletableFuture<Void> registerFullRestoreBarrier(String orderId) {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        fullRestoreBarriers.put(orderId, barrier);
        return barrier;
    }

    /** 支付写入先等待同订单已提交的完整恢复，保证双队列调度不会颠倒单订单状态版本。 */
    private void awaitFullRestoreBarrier(String orderId, long deadlineNanos) {
        CompletableFuture<Void> barrier = fullRestoreBarriers.get(orderId);
        if (barrier == null) {
            return;
        }
        try {
            barrier.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.redisWriteRejected("restore_wait_interrupted");
            throw unavailable(
                    "Membership payment patch prerequisite was interrupted.", exception);
        } catch (TimeoutException exception) {
            metrics.redisWriteRejected("restore_wait_timeout");
            throw unavailable("Membership payment patch prerequisite timed out.", exception);
        } catch (ExecutionException exception) {
            metrics.redisWriteRejected("restore_prerequisite_failed");
            throw unavailable(
                    "Membership payment patch prerequisite failed.", exception.getCause());
        }
    }

    private void completeRestoreBarrier(PendingWrite pending) {
        CompletableFuture<Void> barrier = pending.restoreBarrier();
        if (barrier != null) {
            barrier.complete(null);
            fullRestoreBarriers.remove(pending.snapshot().orderId(), barrier);
        }
    }

    private void failRestoreBarrier(
            String orderId,
            CompletableFuture<Void> barrier,
            Throwable failure) {
        if (barrier != null) {
            barrier.completeExceptionally(Objects.requireNonNull(failure));
            fullRestoreBarriers.remove(orderId, barrier);
        }
    }

    /** 该待处理项绑定模式、固定 lane 与独立结果，确保任何批次失败都能精确完成对应 HTTP 等待者。 */
    private record PendingWrite(
            MembershipOrderSnapshotWriteMode mode,
            MembershipOrderSnapshot snapshot,
            int lane,
            long permitWaitNanos,
            long enqueuedNanos,
            CompletableFuture<Void> restoreBarrier,
            CompletableFuture<CoordinatedWriteResult> result) {
    }

    /** 每条 lane 的两个有界队列共享全局许可，但按写入成本和业务优先级独立组批。 */
    private record LaneQueues(
            ArrayBlockingQueue<PendingWrite> fullRestore,
            ArrayBlockingQueue<PendingWrite> paymentAttemptPatch) {

        private ArrayBlockingQueue<PendingWrite> queueFor(
                MembershipOrderSnapshotWriteMode mode) {
            return mode == MembershipOrderSnapshotWriteMode.FULL_RESTORE
                    ? fullRestore
                    : paymentAttemptPatch;
        }

        private boolean isEmpty() {
            return fullRestore.isEmpty() && paymentAttemptPatch.isEmpty();
        }

        private void clear() {
            fullRestore.clear();
            paymentAttemptPatch.clear();
        }
    }

    /** 该选择结果固定一个同类型队列，确保单个 Pipeline 不混合创建和支付调度份额。 */
    private record SelectedWrite(
            MembershipOrderSnapshotWriteMode mode,
            ArrayBlockingQueue<PendingWrite> queue,
            PendingWrite first) {
    }

    /** 该结果把 Redis 快照与当前请求的非重叠计时绑定，确保由原调用线程写入自己的绿字上下文。 */
    private record CoordinatedWriteResult(
            MembershipOrderSnapshot snapshot,
            MembershipPaymentRedisWriteBreakdown breakdown) {
    }
}
