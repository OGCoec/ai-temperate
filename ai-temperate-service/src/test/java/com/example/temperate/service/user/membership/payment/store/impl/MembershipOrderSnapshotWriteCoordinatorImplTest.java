package com.example.temperate.service.user.membership.payment.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentRedisWriteProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentObservabilityProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingRecorder;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteMode;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 该单元测试是来用小型确定性边界验证跨请求微批的组包顺序、结果完整性和许可释放，不连接真实 Redis。
 */
final class MembershipOrderSnapshotWriteCoordinatorImplTest {

    @Test
    void threeHundredEightyFourQueuedWritesStayWithinSixLaneBatchBoundary() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        List<List<MembershipOrderSnapshot>> batches =
                java.util.Collections.synchronizedList(new ArrayList<>());
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand>
                    commands = List.copyOf(invocation.getArgument(0));
            List<MembershipOrderSnapshot> input = commands.stream()
                    .map(com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand::snapshot)
                    .toList();
            batches.add(input);
            return input.stream()
                    .map(snapshot -> new MembershipOrderSnapshotWriteResult(
                            MembershipOrderSnapshotWriteOutcome.UNCHANGED, snapshot))
                    .toList();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PausedExecutorService worker = new PausedExecutorService(6);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(64, 384, 6));
        coordinator.afterPropertiesSet();
        assertThat(worker.awaitSubmission()).isTrue();

        List<MembershipOrderSnapshot> snapshots = java.util.stream.IntStream.range(0, 384)
                .mapToObj(index -> snapshot(
                        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                                java.nio.ByteBuffer.allocate(16)
                                        .putLong(29L)
                                        .putLong(index + 1L)
                                        .array()),
                        index + 1L))
                .toList();
        List<CompletableFuture<MembershipOrderSnapshot>> results = new ArrayList<>(384);
        try {
            // 同时暂停六个 lane 后逐条确认全局在途数，避免 worker 提前消费掩盖 384 条边界。
            for (int index = 0; index < snapshots.size(); index++) {
                MembershipOrderSnapshot snapshot = snapshots.get(index);
                results.add(CompletableFuture.supplyAsync(
                        () -> coordinator.putAndGet(snapshot), callers));
                awaitInflight(registry, index + 1D);
            }
            worker.startWorker();

            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get(5, TimeUnit.SECONDS))
                        .isEqualTo(snapshots.get(index));
            }
            assertThat(batches.stream().flatMap(List::stream).toList())
                    .containsExactlyInAnyOrderElementsOf(snapshots);
            assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(64));
        } finally {
            worker.startWorker();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    void sixLanesRunAtMostSixPipelinesConcurrently() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        CountDownLatch allPipelinesEntered = new CountDownLatch(6);
        CountDownLatch releasePipelines = new CountDownLatch(1);
        AtomicInteger activePipelines = new AtomicInteger();
        AtomicInteger maximumActivePipelines = new AtomicInteger();
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<MembershipOrderSnapshotWriteCommand> commands =
                    List.copyOf(invocation.getArgument(0));
            int active = activePipelines.incrementAndGet();
            maximumActivePipelines.accumulateAndGet(active, Math::max);
            allPipelinesEntered.countDown();
            try {
                if (!releasePipelines.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test pipeline release timed out.");
                }
                return commands.stream()
                        .map(command -> new MembershipOrderSnapshotWriteResult(
                                MembershipOrderSnapshotWriteOutcome.UNCHANGED,
                                command.snapshot()))
                        .toList();
            } finally {
                activePipelines.decrementAndGet();
            }
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService worker = Executors.newFixedThreadPool(6);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(64, 384, 6));
        coordinator.afterPropertiesSet();
        List<MembershipOrderSnapshot> snapshots = java.util.stream.IntStream.range(0, 6)
                .mapToObj(lane -> snapshotsForLane(lane, 6, 1, 1_000L + lane).getFirst())
                .toList();
        List<CompletableFuture<MembershipOrderSnapshot>> results = snapshots.stream()
                .map(snapshot -> CompletableFuture.supplyAsync(
                        () -> coordinator.putAndGet(snapshot), callers))
                .toList();
        try {
            assertThat(allPipelinesEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActivePipelines).hasValue(6);
            assertThat(activePipelines).hasValue(6);
            assertThat(coordinator.runtimeSnapshot().inflight()).isEqualTo(6);
            releasePipelines.countDown();
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get(5, TimeUnit.SECONDS))
                        .isEqualTo(snapshots.get(index));
            }
        } finally {
            releasePipelines.countDown();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    void queuedWritesAcrossTwoLanesAllCompleteWithinTheGlobalBound() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        CountDownLatch firstBatchEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);
        List<List<MembershipOrderSnapshot>> batches =
                java.util.Collections.synchronizedList(new ArrayList<>());
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand>
                    commands = List.copyOf(invocation.getArgument(0));
            List<MembershipOrderSnapshot> input = commands.stream()
                    .map(com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand::snapshot)
                    .toList();
            batches.add(input);
            if (batches.size() == 1) {
                firstBatchEntered.countDown();
                if (!releaseFirstBatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test batch release timed out.");
                }
            }
            return input.stream()
                    .map(snapshot -> new MembershipOrderSnapshotWriteResult(
                            MembershipOrderSnapshotWriteOutcome.UNCHANGED, snapshot))
                    .toList();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService worker = Executors.newFixedThreadPool(2);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(2, 4));
        coordinator.afterPropertiesSet();
        try {
            MembershipOrderSnapshot first = snapshot("AQEBAQEBAQEBAQEBAQEBAQ", 1L);
            MembershipOrderSnapshot second = snapshot("AgICAgICAgICAgICAgICAg", 2L);
            MembershipOrderSnapshot third = snapshot("AwMDAwMDAwMDAwMDAwMDAw", 3L);
            CompletableFuture<MembershipOrderSnapshot> firstResult =
                    CompletableFuture.supplyAsync(() -> coordinator.putAndGet(first), callers);
            assertThat(firstBatchEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<MembershipOrderSnapshot> secondResult =
                    CompletableFuture.supplyAsync(() -> coordinator.putAndGet(second), callers);
            CompletableFuture<MembershipOrderSnapshot> thirdResult =
                    CompletableFuture.supplyAsync(() -> coordinator.putAndGet(third), callers);
            awaitInflight(registry, 3D);
            releaseFirstBatch.countDown();

            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo(second);
            assertThat(thirdResult.get(5, TimeUnit.SECONDS)).isEqualTo(third);
            assertThat(batches.stream().flatMap(List::stream).toList())
                    .containsExactlyInAnyOrder(first, second, third);
        } finally {
            releaseFirstBatch.countDown();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    void sameOrderKeepsFifoWhenFullRestoreAndPatchShareOneLane() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        List<MembershipOrderSnapshotWriteCommand> executed =
                java.util.Collections.synchronizedList(new ArrayList<>());
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<MembershipOrderSnapshotWriteCommand> commands =
                    List.copyOf(invocation.getArgument(0));
            executed.addAll(commands);
            return commands.stream()
                    .map(command -> new MembershipOrderSnapshotWriteResult(
                            MembershipOrderSnapshotWriteOutcome.UNCHANGED,
                            command.snapshot()))
                    .toList();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PausedExecutorService worker = new PausedExecutorService();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(64, 384));
        coordinator.afterPropertiesSet();
        assertThat(worker.awaitSubmission()).isTrue();

        MembershipOrderSnapshot first = snapshot("AQEBAQEBAQEBAQEBAQEBAQ", 1L);
        MembershipOrderSnapshot second = snapshot(first.orderId(), 2L);
        try {
            CompletableFuture<MembershipOrderSnapshot> firstResult =
                    CompletableFuture.supplyAsync(() -> coordinator.putAndGet(first), callers);
            awaitInflight(registry, 1D);
            CountDownLatch patchStarted = new CountDownLatch(1);
            CompletableFuture<MembershipOrderSnapshot> secondResult =
                    CompletableFuture.supplyAsync(
                            () -> {
                                patchStarted.countDown();
                                return coordinator.patchPaymentAttempt(second);
                            }, callers);
            assertThat(patchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            // 支付 Patch 必须停在同订单完整写入屏障外，不能提前占用 Redis 在途许可或进入支付队列。
            assertThat(registry.get("membership_payment_redis_write_inflight")
                            .gauge()
                            .value())
                    .isEqualTo(1D);
            worker.startWorker();

            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo(second);
            assertThat(executed)
                    .extracting(
                            MembershipOrderSnapshotWriteCommand::mode,
                            command -> command.snapshot().stateVersion())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    MembershipOrderSnapshotWriteMode.FULL_RESTORE, 1L),
                            org.assertj.core.groups.Tuple.tuple(
                                    MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH, 2L));
        } finally {
            worker.startWorker();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    void paymentQueueRunsAfterFourCreateBatchesWhenBothQueuesHaveWork() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        List<MembershipOrderSnapshotWriteMode> executed =
                java.util.Collections.synchronizedList(new ArrayList<>());
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<MembershipOrderSnapshotWriteCommand> commands =
                    List.copyOf(invocation.getArgument(0));
            executed.add(commands.getFirst().mode());
            return commands.stream()
                    .map(command -> new MembershipOrderSnapshotWriteResult(
                            MembershipOrderSnapshotWriteOutcome.UNCHANGED,
                            command.snapshot()))
                    .toList();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PausedExecutorService worker = new PausedExecutorService();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(1, 12));
        coordinator.afterPropertiesSet();
        assertThat(worker.awaitSubmission()).isTrue();

        List<MembershipOrderSnapshot> creates = snapshotsForLane(0, 5, 100L);
        MembershipOrderSnapshot payment = snapshotsForLane(0, 1, 900L).getFirst();
        List<CompletableFuture<MembershipOrderSnapshot>> results = new ArrayList<>();
        try {
            for (MembershipOrderSnapshot create : creates) {
                results.add(CompletableFuture.supplyAsync(
                        () -> coordinator.putAndGet(create), callers));
            }
            awaitInflight(registry, 5D);
            results.add(CompletableFuture.supplyAsync(
                    () -> coordinator.patchPaymentAttempt(payment), callers));
            awaitInflight(registry, 6D);
            worker.startWorker();

            for (CompletableFuture<MembershipOrderSnapshot> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
            }
            assertThat(executed).containsExactly(
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE,
                    MembershipOrderSnapshotWriteMode.PAYMENT_ATTEMPT_PATCH,
                    MembershipOrderSnapshotWriteMode.FULL_RESTORE);
        } finally {
            worker.startWorker();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test
    void incompletePipelineResultFailsTheBatchAndReleasesCapacity() {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        when(store.writeAll(any()))
                .thenReturn(List.of())
                .thenAnswer(invocation -> {
                    List<com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCommand>
                            commands = List.copyOf(invocation.getArgument(0));
                    return commands.stream()
                            .map(command -> new MembershipOrderSnapshotWriteResult(
                                    MembershipOrderSnapshotWriteOutcome.UNCHANGED,
                                    command.snapshot()))
                            .toList();
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService worker = Executors.newFixedThreadPool(2);
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(1, 1));
        coordinator.afterPropertiesSet();
        try {
            assertThatThrownBy(() -> coordinator.putAndGet(
                            snapshot("BAQEBAQEBAQEBAQEBAQEBA", 4L)))
                    .isInstanceOf(MembershipPaymentInfrastructureException.class)
                    .hasMessageContaining("incomplete");

            MembershipOrderSnapshot retry = snapshot("BQUFBQUFBQUFBQUFBQUFBQ", 5L);
            assertThat(coordinator.putAndGet(retry)).isEqualTo(retry);
            assertThat(registry.get("membership_payment_redis_write_inflight")
                            .gauge()
                            .value())
                    .isZero();
        } finally {
            coordinator.destroy();
            worker.shutdownNow();
        }
    }

    @Test
    void connectionFailureRecordsOneLowCardinalityBatchDiagnostic() {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        when(store.writeAll(any())).thenThrow(
                new RedisConnectionFailureException("test connection failure"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService worker = Executors.newFixedThreadPool(2);
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(1, 1));
        coordinator.afterPropertiesSet();
        try {
            assertThatThrownBy(() -> coordinator.putAndGet(
                            snapshot("BwcHBwcHBwcHBwcHBwcHBw", 7L)))
                    .isInstanceOf(MembershipPaymentInfrastructureException.class);

            assertThat(registry.get("membership_payment_redis_write_rejected_total")
                            .tag("outcome", "batch_connection")
                            .counter()
                            .count())
                    .isEqualTo(1D);
        } finally {
            coordinator.destroy();
            worker.shutdownNow();
        }
    }

    @Test
    void runtimeSnapshotReportsConfiguredAndLiveBoundedCapacity() throws Exception {
        MembershipOrderSnapshotStore store = mock(MembershipOrderSnapshotStore.class);
        when(store.writeAll(any())).thenAnswer(invocation -> {
            List<MembershipOrderSnapshotWriteCommand> commands =
                    List.copyOf(invocation.getArgument(0));
            return commands.stream()
                    .map(command -> new MembershipOrderSnapshotWriteResult(
                            MembershipOrderSnapshotWriteOutcome.UNCHANGED,
                            command.snapshot()))
                    .toList();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PausedExecutorService worker = new PausedExecutorService(6);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        MembershipOrderSnapshotWriteCoordinatorImpl coordinator = coordinator(
                store, registry, worker, properties(64, 384, 6));
        coordinator.afterPropertiesSet();
        assertThat(worker.awaitSubmission()).isTrue();

        CompletableFuture<MembershipOrderSnapshot> result = CompletableFuture.supplyAsync(
                () -> coordinator.putAndGet(snapshot("BgYGBgYGBgYGBgYGBgYGBg", 6L)), callers);
        try {
            awaitInflight(registry, 1D);
            assertThat(coordinator.runtimeSnapshot())
                    .satisfies(snapshot -> {
                        assertThat(snapshot.accepting()).isTrue();
                        assertThat(snapshot.configuredBatchSize()).isEqualTo(64);
                        assertThat(snapshot.configuredLaneCount()).isEqualTo(6);
                        assertThat(snapshot.maximumInflight()).isEqualTo(384);
                        assertThat(snapshot.inflight()).isEqualTo(1);
                        assertThat(snapshot.availablePermits()).isEqualTo(383);
                        assertThat(snapshot.fullRestoreQueueDepths()).hasSize(6);
                        assertThat(snapshot.fullRestoreQueueDepths()).containsOnly(0, 1);
                        assertThat(snapshot.paymentAttemptPatchQueueDepths())
                                .containsExactly(0, 0, 0, 0, 0, 0);
                        assertThat(snapshot.queueDepths()).hasSize(6);
                        assertThat(snapshot.queueDepths()).containsOnly(0, 1);
                    });
            worker.startWorker();
            assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            worker.startWorker();
            coordinator.destroy();
            callers.shutdownNow();
            worker.shutdownNow();
        }
    }

    private static MembershipOrderSnapshotWriteCoordinatorImpl coordinator(
            MembershipOrderSnapshotStore store,
            SimpleMeterRegistry registry,
            ExecutorService worker,
            MembershipPaymentRedisWriteProperties properties) {
        MembershipPaymentMetrics metrics = new MembershipPaymentMetrics(registry);
        return new MembershipOrderSnapshotWriteCoordinatorImpl(
                store,
                properties,
                metrics,
                new MembershipPaymentTimingRecorder(
                        metrics,
                        new MembershipPaymentObservabilityProperties(
                                false, false, 0D, Duration.ofSeconds(1), "test", false),
                        Clock.systemUTC()),
                worker);
    }

    private static MembershipPaymentRedisWriteProperties properties(
            int batchSize,
            int maximumInflight) {
        return properties(batchSize, maximumInflight, 2);
    }

    private static MembershipPaymentRedisWriteProperties properties(
            int batchSize,
            int maximumInflight,
            int laneCount) {
        return new MembershipPaymentRedisWriteProperties(
                batchSize,
                laneCount,
                Duration.ofMillis(5),
                maximumInflight,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
    }

    private static void awaitInflight(
            SimpleMeterRegistry registry,
            double expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.get("membership_payment_redis_write_inflight")
                        .gauge()
                        .value()
                != expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Redis write inflight gauge did not reach " + expected);
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }

    private static MembershipOrderSnapshot snapshot(String orderId, long version) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-24T12:00:00Z");
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                orderId,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PENDING_PAYMENT,
                UUID.nameUUIDFromBytes(orderId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null,
                now.plusMinutes(5),
                null,
                null,
                version,
                now,
                now);
    }

    private static List<MembershipOrderSnapshot> snapshotsForLane(
            int lane,
            int count,
            long firstVersion) {
        return snapshotsForLane(lane, 2, count, firstVersion);
    }

    private static List<MembershipOrderSnapshot> snapshotsForLane(
            int lane,
            int laneCount,
            int count,
            long firstVersion) {
        List<MembershipOrderSnapshot> snapshots = new ArrayList<>(count);
        long candidate = firstVersion;
        while (snapshots.size() < count) {
            String orderId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.nio.ByteBuffer.allocate(16)
                            .putLong(41L)
                            .putLong(candidate)
                            .array());
            if (Math.floorMod(orderId.hashCode(), laneCount) == lane) {
                snapshots.add(snapshot(orderId, candidate));
            }
            candidate += 1L;
        }
        return snapshots;
    }

    /**
     * 该测试执行器是来先接受协调器 drain loop、再由测试显式放行，保证队列边界断言不依赖线程调度运气。
     */
    private static final class PausedExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;
        private final int workerCount;
        private final List<Runnable> workerTasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final CountDownLatch submitted;

        private PausedExecutorService() {
            this(2);
        }

        private PausedExecutorService(int workerCount) {
            this.workerCount = workerCount;
            this.delegate = Executors.newFixedThreadPool(workerCount);
            this.submitted = new CountDownLatch(workerCount);
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown.get() || workerTasks.size() >= workerCount) {
                throw new IllegalStateException(
                        "Paused test executor exceeded its configured worker count.");
            }
            workerTasks.add(command);
            submitted.countDown();
        }

        private boolean awaitSubmission() throws InterruptedException {
            return submitted.await(5, TimeUnit.SECONDS);
        }

        private void startWorker() {
            if (started.compareAndSet(false, true)) {
                workerTasks.forEach(delegate::execute);
            }
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
