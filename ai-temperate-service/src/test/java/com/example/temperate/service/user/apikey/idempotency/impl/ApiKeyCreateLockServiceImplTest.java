package com.example.temperate.service.user.apikey.idempotency.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.apikey.idempotency.ApiKeyCreateIdempotencyHasher;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 该测试是来约束 API Key 创建锁的一秒等待、看门狗调用、Redis 降级、线程中断与安全解锁行为。
 */
final class ApiKeyCreateLockServiceImplTest {

    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private RedissonClient redissonClient;
    private RLock lock;
    private SimpleMeterRegistry meterRegistry;
    private ApiKeyCreateLockServiceImpl service;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        meterRegistry = new SimpleMeterRegistry();
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        service = new ApiKeyCreateLockServiceImpl(
                redissonClient,
                new RedisKeyFactory("test"),
                new ApiKeyCreateIdempotencyHasher(new byte[32]),
                meterRegistry);
    }

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
        meterRegistry.close();
    }

    @Test
    void successfulActionFinishesBeforeTheCurrentThreadUnlocks() throws Exception {
        AtomicBoolean actionFinished = new AtomicBoolean();
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(actionFinished.get()).isTrue();
            return null;
        }).when(lock).unlock();

        String result = service.execute(17L, IDEMPOTENCY_KEY, () -> {
            actionFinished.set(true);
            return "created";
        });

        assertThat(result).isEqualTo("created");
        verify(lock).tryLock(1L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    void contentionReturnsInProgressWithoutExecutingTheDatabasePath() throws Exception {
        AtomicBoolean actionRan = new AtomicBoolean();
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(17L, IDEMPOTENCY_KEY, () -> {
            actionRan.set(true);
            return "unexpected";
        }))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.API_KEY_CREATE_IN_PROGRESS);

        assertThat(actionRan).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void redisCommandFailureFallsBackToTheDatabaseProtectedAction() throws Exception {
        // Redisson 延迟初始化后，Redis 连接错误会在 tryLock 命令阶段出现，而不是创建 Bean 或取得代理锁时出现。
        when(lock.tryLock(1L, TimeUnit.SECONDS))
                .thenThrow(new IllegalStateException("redis unavailable"));

        String result = service.execute(17L, IDEMPOTENCY_KEY, () -> "database-result");

        assertThat(result).isEqualTo("database-result");
        assertThat(meterRegistry.counter("api_key.create.lock.redis_fallback").count())
                .isEqualTo(1.0d);
    }

    @Test
    void interruptedWaitRestoresTheFlagAndDoesNotContinue() throws Exception {
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        assertThatThrownBy(() -> service.execute(17L, IDEMPOTENCY_KEY, () -> "unexpected"))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.API_KEY_CREATE_COORDINATION_UNAVAILABLE);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void unlockFailureDoesNotOverwriteTheCommittedBusinessResult() throws Exception {
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

        assertDoesNotThrow(() -> assertThat(
                service.execute(17L, IDEMPOTENCY_KEY, () -> "created"))
                .isEqualTo("created"));
        assertThat(meterRegistry.counter("api_key.create.lock.unlock_failure").count())
                .isEqualTo(1.0d);
    }

    @Test
    void doesNotUnlockWhenTheCurrentThreadNoLongerOwnsTheLock() throws Exception {
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThat(service.execute(17L, IDEMPOTENCY_KEY, () -> "created"))
                .isEqualTo("created");

        verify(lock, never()).unlock();
    }
}
