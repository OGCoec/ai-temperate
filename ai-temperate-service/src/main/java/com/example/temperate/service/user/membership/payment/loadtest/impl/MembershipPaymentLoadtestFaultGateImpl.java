package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
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

    private final MembershipPaymentLoadtestProperties properties;
    private final AtomicReference<ArmedFault> callbackCompleteFault =
            new AtomicReference<>();
    private final AtomicLong callbackCompleteFailureCount = new AtomicLong();

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

    /** 一次性故障使用单调时间设置两分钟上限，测试中断后不会在应用内无限期保持武装。 */
    private record ArmedFault(String orderId, long expiresAtNanos) {

        private boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0L;
        }
    }
}
