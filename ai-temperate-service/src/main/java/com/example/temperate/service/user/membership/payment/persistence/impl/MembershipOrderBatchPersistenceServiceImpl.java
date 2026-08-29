package com.example.temperate.service.user.membership.payment.persistence.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.OrderPersistToken;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.OrderPersistenceQueue;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerOutcome;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在 Redisson 看门狗单例锁内恢复和领取订单版本，批量读取实时 Hash、提交数据库后再精确完成令牌。
 *
 * <p>锁仅用于协调调度并减少重复 I/O；数据库 stateVersion 条件更新和 Redis 精确令牌仍是最终幂等边界。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderBatchPersistenceServiceImpl
        implements MembershipOrderBatchPersistenceService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipOrderBatchPersistenceServiceImpl.class);

    private final RedissonClient redissonClient;
    private final RedisKeyFactory keyFactory;
    private final OrderPersistenceQueue persistenceQueue;
    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderPersistenceService persistenceService;
    private final MembershipPaymentProperties.OrderPersist properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public MembershipOrderBatchPersistenceServiceImpl(
            RedissonClient redissonClient,
            RedisKeyFactory keyFactory,
            OrderPersistenceQueue persistenceQueue,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderPersistenceService persistenceService,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.persistenceQueue = Objects.requireNonNull(persistenceQueue);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.properties = Objects.requireNonNull(properties).orderPersist();
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public MembershipPaymentWorkerRunResult flushOneRun() {
        long startedNanos = System.nanoTime();
        MembershipPaymentWorkerRunResult runResult = MembershipPaymentWorkerRunResult.empty(
                MembershipPaymentWorkerOutcome.LOCK_UNAVAILABLE);
        RLock lock = redissonClient.getLock(keyFactory.orderPersistenceLockKey());
        boolean acquired = false;
        try {
            // 不传 leaseTime 以启用 Redisson 看门狗；有界等待避免调度线程无限阻塞。
            acquired = lock.tryLock(
                    Math.max(1L, properties.lockWait().toMillis()),
                    TimeUnit.MILLISECONDS);
            if (!acquired) {
                return runResult;
            }
            runResult = flushWhileLocked();
        } catch (InterruptedException exception) {
            runResult = MembershipPaymentWorkerRunResult.empty(
                    MembershipPaymentWorkerOutcome.RETRY);
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            runResult = MembershipPaymentWorkerRunResult.empty(
                    MembershipPaymentWorkerOutcome.FAILED);
            throw exception;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (RuntimeException exception) {
                    LOGGER.error(
                            "Membership order persistence lock release failed; "
                                    + "traceId={} reason={}",
                            MembershipPaymentTraceContext.currentTraceId(),
                            exception.getClass().getSimpleName());
                }
            }
            updateQueueGauges();
            recordWorkerRun(runResult, startedNanos);
        }
        return runResult;
    }

    private MembershipPaymentWorkerRunResult flushWhileLocked() {
        long now = clock.millis();
        persistenceQueue.recoverTimedOut(
                now - properties.processingTimeout().toMillis(),
                properties.batchSize(),
                now);
        int batches = 0;
        int claimedItems = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            List<OrderPersistToken> tokens = persistenceQueue.claim(
                    properties.batchSize(), clock.millis());
            if (tokens.isEmpty()) {
                return new MembershipPaymentWorkerRunResult(
                        batches, claimedItems, MembershipPaymentWorkerOutcome.DRAINED);
            }
            batches++;
            claimedItems += tokens.size();
            if (!persist(tokens)) {
                return new MembershipPaymentWorkerRunResult(
                        batches, claimedItems, MembershipPaymentWorkerOutcome.RETRY);
            }
        }
        return new MembershipPaymentWorkerRunResult(
                batches, claimedItems, MembershipPaymentWorkerOutcome.CAPACITY);
    }

    private void recordWorkerRun(
            MembershipPaymentWorkerRunResult result,
            long startedNanos) {
        try {
            metrics.workerRunCompleted(
                    MembershipPaymentWorker.ORDER_PERSIST,
                    result.batches(),
                    result.claimedItems(),
                    result.outcome().name().toLowerCase(java.util.Locale.ROOT),
                    System.nanoTime() - startedNanos,
                    Thread.currentThread().getName());
        } catch (RuntimeException exception) {
            // 压测观测不得影响锁释放和刷盘语义；采样缺失由正式测试门禁单独裁决。
            LOGGER.debug(
                    "Membership order persistence worker observation failed; traceId={}",
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }

    private boolean persist(List<OrderPersistToken> tokens) {
        try {
            Map<String, Long> highestClaimedVersions = tokens.stream()
                    .collect(Collectors.toMap(
                            OrderPersistToken::orderId,
                            OrderPersistToken::stateVersion,
                            Math::max,
                            LinkedHashMap::new));
            Map<String, MembershipOrderSnapshot> snapshots = snapshotStore.findAll(
                    highestClaimedVersions.keySet());
            List<MembershipOrderSnapshot> persistable = new ArrayList<>();
            for (Map.Entry<String, Long> entry : highestClaimedVersions.entrySet()) {
                MembershipOrderSnapshot snapshot = snapshots.get(entry.getKey());
                if (snapshot != null && snapshot.stateVersion() >= entry.getValue()) {
                    persistable.add(snapshot);
                }
            }
            // Mapper 事务提交成功后才能清理 processing；快照缺失或低版本令牌按损坏项精确完成，避免永久忙循环。
            persistenceService.persist(persistable);
            persistenceQueue.complete(tokens);
            metrics.orderPersisted(persistable.size());
            int discarded = highestClaimedVersions.size() - persistable.size();
            if (discarded > 0) {
                LOGGER.warn(
                        "Membership order persistence tokens were discarded as damaged; "
                                + "traceId={} count={}",
                        MembershipPaymentTraceContext.currentTraceId(),
                        discarded);
            }
            return true;
        } catch (RuntimeException exception) {
            metrics.orderPersistFailure();
            safeRequeue(tokens);
            LOGGER.warn(
                    "Membership order persistence batch will retry; "
                            + "traceId={} count={} reason={}",
                    MembershipPaymentTraceContext.currentTraceId(),
                    tokens.size(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void safeRequeue(Collection<OrderPersistToken> tokens) {
        try {
            persistenceQueue.requeue(tokens, clock.millis());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Membership order persistence requeue failed; "
                            + "traceId={} count={} reason={}",
                    MembershipPaymentTraceContext.currentTraceId(),
                    tokens.size(),
                    exception.getClass().getSimpleName());
        }
    }

    private void updateQueueGauges() {
        try {
            metrics.orderQueueSizes(
                    persistenceQueue.dirtySize(),
                    persistenceQueue.processingSize());
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "Membership order persistence gauges are unavailable; traceId={}",
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }

}
