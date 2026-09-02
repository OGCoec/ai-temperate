package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationLockService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来使用 Redisson 看门狗锁协调单用户订单创建，并在 Redis 协调不可用时拒绝执行强制替换。
 *
 * <p>锁只缩小并发窗口；订单幂等、活动订单唯一性和最终事实仍由 PostgreSQL 事务及唯一索引保证。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderCreationLockServiceImpl
        implements MembershipOrderCreationLockService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipOrderCreationLockServiceImpl.class);
    private static final long WAIT_SECONDS = 1L;

    private final RedissonClient redissonClient;
    private final RedisKeyFactory keyFactory;
    private final Timer waitTimer;
    private final Timer holdTimer;
    private final Counter unavailableCounter;
    private final Counter contentionCounter;
    private final Counter unlockFailureCounter;

    public MembershipOrderCreationLockServiceImpl(
            RedissonClient redissonClient,
            RedisKeyFactory keyFactory,
            MeterRegistry meterRegistry) {
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.waitTimer = registry.timer("membership_payment.order_create.lock.wait");
        this.holdTimer = registry.timer("membership_payment.order_create.lock.hold");
        this.unavailableCounter = registry.counter(
                "membership_payment.order_create.lock.unavailable");
        this.contentionCounter = registry.counter(
                "membership_payment.order_create.lock.contention");
        this.unlockFailureCounter = registry.counter(
                "membership_payment.order_create.lock.unlock_failure");
    }

    /**
     * 在有界等待内取得用户级锁并执行创建动作；Redis 协调失败时禁止进入可能终结旧订单的业务区段。
     */
    @Override
    public <T> T execute(long loginIdentityId, Supplier<T> action) {
        Supplier<T> requiredAction = Objects.requireNonNull(action);
        RLock lock;
        boolean acquired;
        long waitStarted = System.nanoTime();
        try {
            lock = redissonClient.getLock(
                    keyFactory.membershipOrderCreationLockKey(loginIdentityId));
            // 只传等待时间以保持看门狗续租；固定 leaseTime 会让慢事务在执行中失去互斥边界。
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            unavailableCounter.increment();
            throw unavailable("Membership order creation lock wait was interrupted.", exception);
        } catch (RuntimeException exception) {
            unavailableCounter.increment();
            throw unavailable("Membership order creation lock is unavailable.", exception);
        } finally {
            record(waitTimer, waitStarted);
        }
        if (!acquired) {
            contentionCounter.increment();
            throw unavailable("Membership order creation is already in progress.", null);
        }

        long holdStarted = System.nanoTime();
        try {
            return requiredAction.get();
        } finally {
            record(holdTimer, holdStarted);
            release(lock);
        }
    }

    /** 解锁故障不得覆盖已提交的订单结果，后续由 Redisson 看门狗超时回收并通过指标告警。 */
    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            unlockFailureCounter.increment();
            LOGGER.warn(
                    "event=membership_order_create_lock_release_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private static MembershipPaymentException unavailable(
            String message,
            Throwable cause) {
        return cause == null
                ? new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                        message)
                : new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                        message,
                        cause);
    }

    private static void record(Timer timer, long startedNanos) {
        try {
            timer.record(Duration.ofNanos(System.nanoTime() - startedNanos));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=membership_order_create_lock_metric_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "background" : value;
    }
}
