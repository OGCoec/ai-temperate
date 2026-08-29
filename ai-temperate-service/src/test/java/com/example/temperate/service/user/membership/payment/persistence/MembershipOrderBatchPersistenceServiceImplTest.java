package com.example.temperate.service.user.membership.payment.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.persistence.impl.MembershipOrderBatchPersistenceServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.OrderPersistenceQueue;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 该单元测试是来约束第二个五秒任务使用无 leaseTime 的看门狗锁，并且数据库提交后才精确完成 processing 令牌。
 */
final class MembershipOrderBatchPersistenceServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = orderId();

    private RLock lock;
    private OrderPersistenceQueue queue;
    private MembershipOrderSnapshotStore snapshotStore;
    private MembershipOrderPersistenceService persistenceService;
    private MembershipPaymentMetrics metrics;
    private MembershipOrderBatchPersistenceService service;
    private OrderPersistToken token;

    @BeforeEach
    void setUp() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        queue = mock(OrderPersistenceQueue.class);
        snapshotStore = mock(MembershipOrderSnapshotStore.class);
        persistenceService = mock(MembershipOrderPersistenceService.class);
        metrics = mock(MembershipPaymentMetrics.class);
        RedisKeyFactory keyFactory = new RedisKeyFactory("test");
        token = new OrderPersistToken(ORDER_ID, 2L, NOW.toEpochMilli());
        when(redissonClient.getLock(keyFactory.orderPersistenceLockKey())).thenReturn(lock);
        when(lock.tryLock(100L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(queue.claim(anyInt(), anyLong()))
                .thenReturn(List.of(token), List.of());
        when(snapshotStore.findAll(any())).thenReturn(Map.of(ORDER_ID, snapshot()));
        service = new MembershipOrderBatchPersistenceServiceImpl(
                redissonClient,
                keyFactory,
                queue,
                snapshotStore,
                persistenceService,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                metrics);
    }

    @Test
    void persistsSnapshotBeforeCompletingTokenAndReleasesOwnedLock() {
        service.flushOneRun();

        InOrder ordered = inOrder(persistenceService, queue, lock);
        ordered.verify(persistenceService).persist(List.of(snapshot()));
        ordered.verify(queue).complete(List.of(token));
        ordered.verify(lock).unlock();
    }

    @Test
    void reportsNaturalWorkerRunBatchAndClaimCounts() {
        service.flushOneRun();

        verify(metrics).workerRunCompleted(
                org.mockito.ArgumentMatchers.eq(MembershipPaymentWorker.ORDER_PERSIST),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("drained"),
                anyLong(),
                anyString());
    }

    @Test
    void persistenceFailureRequeuesExactToken() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(persistenceService).persist(any());

        service.flushOneRun();

        verify(queue).requeue(List.of(token), NOW.toEpochMilli());
        verify(lock).unlock();
    }

    private static MembershipOrderSnapshot snapshot() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PAID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "provider-trade-1",
                now.plusMinutes(5),
                null,
                now,
                2L,
                now.minusMinutes(1),
                now);
    }

    private static String orderId() {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, (byte) 9);
        return new HybridBase64UrlCodec().encode(bytes);
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30), 3));
    }
}
