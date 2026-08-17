package com.example.temperate.service.user.apikey.idempotency.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.apikey.idempotency.ApiKeyCreateIdempotencyHasher;
import com.example.temperate.service.user.apikey.idempotency.ApiKeyCreateLockService;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 该实现是来通过 Redisson 看门狗锁合并同一 API Key 创建意图，并在 Redis 故障时降级到 PostgreSQL 唯一约束路径。
 */
@Service
public final class ApiKeyCreateLockServiceImpl implements ApiKeyCreateLockService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ApiKeyCreateLockServiceImpl.class);
    private static final long WAIT_SECONDS = 1L;

    private final RedissonClient redissonClient;
    private final RedisKeyFactory redisKeyFactory;
    private final ApiKeyCreateIdempotencyHasher hasher;
    private final Timer waitTimer;
    private final Timer holdTimer;
    private final Counter contentionCounter;
    private final Counter redisFallbackCounter;
    private final Counter interruptedCounter;
    private final Counter unlockFailureCounter;

    public ApiKeyCreateLockServiceImpl(
            RedissonClient redissonClient,
            RedisKeyFactory redisKeyFactory,
            ApiKeyCreateIdempotencyHasher hasher,
            MeterRegistry meterRegistry) {
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.redisKeyFactory = Objects.requireNonNull(redisKeyFactory);
        this.hasher = Objects.requireNonNull(hasher);
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.waitTimer = registry.timer("api_key.create.lock.wait");
        this.holdTimer = registry.timer("api_key.create.lock.hold");
        this.contentionCounter = registry.counter("api_key.create.lock.contention");
        this.redisFallbackCounter = registry.counter("api_key.create.lock.redis_fallback");
        this.interruptedCounter = registry.counter("api_key.create.lock.interrupted");
        this.unlockFailureCounter = registry.counter("api_key.create.lock.unlock_failure");
    }

    @Override
    public <T> T execute(
            long loginIdentityId,
            UUID idempotencyKey,
            Supplier<T> action) {
        Supplier<T> requiredAction = Objects.requireNonNull(action);
        String lockKey = redisKeyFactory.apiKeyCreateLockKey(
                hasher.identify(loginIdentityId, idempotencyKey));
        long waitStarted = System.nanoTime();
        RLock lock;
        boolean acquired;
        try {
            lock = redissonClient.getLock(lockKey);
            // 这里只传 waitTime，禁止传 leaseTime；由全局 30 秒看门狗持续续租直到事务提交后解锁。
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            interruptedCounter.increment();
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.API_KEY_CREATE_COORDINATION_UNAVAILABLE,
                    "API Key create lock wait was interrupted");
        } catch (RuntimeException exception) {
            redisFallbackCounter.increment();
            LOGGER.warn(
                    "event=api_key_create_lock_redis_fallback traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
            // 数据库 INSERT 仍使用 ON CONFLICT 和唯一索引，因此 Redis 不可用时不会放大为重复创建。
            return requiredAction.get();
        } finally {
            recordTimer(waitTimer, waitStarted, "wait");
        }

        if (!acquired) {
            contentionCounter.increment();
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.API_KEY_CREATE_IN_PROGRESS,
                    "API Key creation is already in progress");
        }

        long holdStarted = System.nanoTime();
        try {
            return requiredAction.get();
        } finally {
            recordTimer(holdTimer, holdStarted, "hold");
            release(lock);
        }
    }

    /**
     * 解锁失败不得覆盖数据库业务结果；只有 Redisson 实例失联后锁才会按看门狗超时释放，实例仍存活时必须依靠指标告警处理。
     */
    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            unlockFailureCounter.increment();
            LOGGER.warn(
                    "event=api_key_create_lock_release_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
    }

    /** 指标故障不得改变加锁、数据库事务或解锁的业务控制流。 */
    private static void recordTimer(Timer timer, long startedNanos, String operation) {
        try {
            timer.record(Duration.ofNanos(System.nanoTime() - startedNanos));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=api_key_create_lock_metric_failed traceId={} operation={} cause={}",
                    traceId(),
                    operation,
                    exception.getClass().getSimpleName());
        }
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "background" : value;
    }
}
