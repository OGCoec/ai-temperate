package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在指定订单即将执行 Redis callback complete 前只失败一次，使正式 requeue/恢复链路可被真实 JMeter 验收。
 *
 * <p>开关关闭时禁止武装；故障在抛出前先通过 CAS 消耗，避免后台调度形成无限失败或无限重入。</p>
 */
@Service
public final class MembershipPaymentLoadtestFaultGateImpl
        implements MembershipPaymentLoadtestFaultGate {

    private static final long ARM_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Duration MAX_CONTROL_DURATION = Duration.ofSeconds(180);
    private static final int MAX_CONCURRENT_CALLBACK_HOLDS = 64;

    private final MembershipPaymentLoadtestProperties properties;
    private final AtomicReference<ArmedFault> callbackCompleteFault =
            new AtomicReference<>();
    private final AtomicLong callbackCompleteFailureCount = new AtomicLong();
    private final ConcurrentHashMap<String, ArmedHold> callbackHolds =
            new ConcurrentHashMap<>();
    private final AtomicLong callbackWorkerPausedUntilNanos = new AtomicLong();
    private final AtomicLong orderPersistenceWorkerPausedUntilNanos = new AtomicLong();

    public MembershipPaymentLoadtestFaultGateImpl(
            MembershipPaymentLoadtestProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public long armCallbackCompleteFailure(String orderId) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        String canonicalOrderId = new MembershipOrderRedisId(orderId).value();
        ArmedFault replacement = new ArmedFault(
                canonicalOrderId,
                System.nanoTime() + ARM_TTL_NANOS);
        while (true) {
            ArmedFault existing = callbackCompleteFault.get();
            if (existing != null && !existing.expired()) {
                throw new IllegalStateException(
                        "A membership payment callback complete fault is already armed.");
            }
            if (callbackCompleteFault.compareAndSet(existing, replacement)) {
                return callbackCompleteFailureCount.get();
            }
        }
    }

    @Override
    public void failBeforeCallbackCompleteIfArmed(
            Collection<PaymentCallbackCompletion> completions) {
        ArmedFault armed = callbackCompleteFault.get();
        if (armed == null || completions == null) {
            return;
        }
        if (armed.expired()) {
            callbackCompleteFault.compareAndSet(armed, null);
            return;
        }
        boolean targetCompletes = completions.stream()
                .filter(Objects::nonNull)
                .map(PaymentCallbackCompletion::orderId)
                .anyMatch(armed.orderId()::equals);
        if (targetCompletes
                && callbackCompleteFault.compareAndSet(armed, null)) {
            callbackCompleteFailureCount.incrementAndGet();
            throw new IllegalStateException(
                    "Injected loadtest callback complete failure.");
        }
    }

    @Override
    public long callbackCompleteFailureCount() {
        return callbackCompleteFailureCount.get();
    }

    @Override
    public void armCallbackHold(String orderId, Duration duration) {
        requireEnabled();
        Duration validDuration = boundedDuration(duration);
        String canonicalOrderId = new MembershipOrderRedisId(orderId).value();
        ArmedHold replacement = new ArmedHold(
                canonicalOrderId,
                System.nanoTime() + validDuration.toNanos());
        // 全阶段矩阵会并行保持不同订单；同步区只保护有界数量与替换，不执行 Redis 或外部 I/O。
        synchronized (callbackHolds) {
            removeExpiredHolds();
            if (!callbackHolds.containsKey(canonicalOrderId)
                    && callbackHolds.size() >= MAX_CONCURRENT_CALLBACK_HOLDS) {
                throw new IllegalStateException(
                        "The membership payment callback hold capacity is exhausted.");
            }
            callbackHolds.put(canonicalOrderId, replacement);
        }
    }

    @Override
    public boolean callbackHeld(String orderId) {
        if (!properties.enabled()) {
            return false;
        }
        String canonicalOrderId = new MembershipOrderRedisId(orderId).value();
        return activeHold(canonicalOrderId) != null;
    }

    @Override
    public long callbackHoldRemainingMillis(String orderId) {
        if (!callbackHeld(orderId)) {
            return 0L;
        }
        ArmedHold active = activeHold(new MembershipOrderRedisId(orderId).value());
        return active == null ? 0L : remainingMillis(active.expiresAtNanos());
    }

    @Override
    public void releaseCallbackHold(String orderId) {
        if (!properties.enabled()) {
            return;
        }
        String canonicalOrderId = new MembershipOrderRedisId(orderId).value();
        callbackHolds.remove(canonicalOrderId);
    }

    @Override
    public void pauseCallbackWorker(Duration duration) {
        requireEnabled();
        callbackWorkerPausedUntilNanos.set(
                System.nanoTime() + boundedDuration(duration).toNanos());
    }

    @Override
    public void resumeCallbackWorker() {
        callbackWorkerPausedUntilNanos.set(0L);
    }

    @Override
    public boolean callbackWorkerPaused() {
        return properties.enabled()
                && remainingMillis(callbackWorkerPausedUntilNanos.get()) > 0L;
    }

    @Override
    public long callbackWorkerPauseRemainingMillis() {
        return properties.enabled()
                ? remainingMillis(callbackWorkerPausedUntilNanos.get())
                : 0L;
    }

    @Override
    public void pauseOrderPersistenceWorker(Duration duration) {
        requireEnabled();
        orderPersistenceWorkerPausedUntilNanos.set(
                System.nanoTime() + boundedDuration(duration).toNanos());
    }

    @Override
    public void resumeOrderPersistenceWorker() {
        orderPersistenceWorkerPausedUntilNanos.set(0L);
    }

    @Override
    public boolean orderPersistenceWorkerPaused() {
        return properties.enabled()
                && remainingMillis(orderPersistenceWorkerPausedUntilNanos.get()) > 0L;
    }

    @Override
    public long orderPersistenceWorkerPauseRemainingMillis() {
        return properties.enabled()
                ? remainingMillis(orderPersistenceWorkerPausedUntilNanos.get())
                : 0L;
    }

    private ArmedHold activeHold(String canonicalOrderId) {
        ArmedHold active = callbackHolds.get(canonicalOrderId);
        if (active != null && active.expired()) {
            callbackHolds.remove(canonicalOrderId, active);
            return null;
        }
        return active;
    }

    private void removeExpiredHolds() {
        callbackHolds.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
    }

    private static Duration boundedDuration(Duration duration) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()
                || duration.compareTo(MAX_CONTROL_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "Loadtest control duration must be between 1 and 180 seconds.");
        }
        return duration;
    }

    private static long remainingMillis(long expiresAtNanos) {
        if (expiresAtNanos <= 0L) {
            return 0L;
        }
        long remaining = expiresAtNanos - System.nanoTime();
        if (remaining <= 0L) {
            return 0L;
        }
        return Math.max(1L, Duration.ofNanos(remaining).toMillis());
    }

    /** 一次性故障使用单调时间设置两分钟上限，测试中断后不会在应用内无限期保持武装。 */
    private record ArmedFault(String orderId, long expiresAtNanos) {

        private boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0L;
        }
    }

    /** 单订单 hold 使用单调时钟自动失效，进程异常后不会无限阻塞 callback ready 队列。 */
    private record ArmedHold(String orderId, long expiresAtNanos) {

        private boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0L;
        }
    }
}
