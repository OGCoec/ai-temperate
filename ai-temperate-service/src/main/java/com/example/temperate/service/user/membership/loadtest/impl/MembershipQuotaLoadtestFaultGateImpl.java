package com.example.temperate.service.user.membership.loadtest.impl;

import com.example.temperate.service.user.membership.loadtest.MembershipQuotaLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把一次预扣事务回滚故障限制到会员支付压测白名单用户，并在两分钟后自动解除未消费武装。
 *
 * <p>故障在额度、Usage 和明细写入后通过 CAS 消耗再抛出，使 Spring 本地事务回滚全部写入；
 * 先消费后抛错可避免调度或客户端重试形成无限失败。</p>
 */
@Service
public final class MembershipQuotaLoadtestFaultGateImpl
        implements MembershipQuotaLoadtestFaultGate {

    private static final long ARM_TTL_NANOS = Duration.ofMinutes(2).toNanos();

    private final MembershipPaymentLoadtestProperties properties;
    private final AtomicReference<ArmedRollback> armedRollback =
            new AtomicReference<>();
    private final AtomicLong failureCount = new AtomicLong();

    public MembershipQuotaLoadtestFaultGateImpl(
            MembershipPaymentLoadtestProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public long armReservationRollback(long userId) {
        requireAllowed(userId);
        ArmedRollback replacement = new ArmedRollback(
                userId,
                System.nanoTime() + ARM_TTL_NANOS);
        while (true) {
            ArmedRollback existing = activeRollback();
            if (existing != null) {
                throw new IllegalStateException(
                        "A membership quota rollback fault is already armed.");
            }
            if (armedRollback.compareAndSet(null, replacement)) {
                return failureCount.get();
            }
        }
    }

    @Override
    public void failAfterReservationIfArmed(long userId) {
        ArmedRollback current = activeRollback();
        if (current == null || current.userId() != userId) {
            return;
        }
        if (armedRollback.compareAndSet(current, null)) {
            failureCount.incrementAndGet();
            throw new IllegalStateException(
                    "Injected loadtest membership quota reservation rollback.");
        }
    }

    @Override
    public boolean reservationRollbackArmed() {
        return activeRollback() != null;
    }

    @Override
    public long reservationRollbackFailureCount() {
        return failureCount.get();
    }

    private ArmedRollback activeRollback() {
        ArmedRollback current = armedRollback.get();
        if (current != null && current.expired()) {
            armedRollback.compareAndSet(current, null);
            return null;
        }
        return current;
    }

    private void requireAllowed(long userId) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        if (!properties.allowedUserIds().contains(userId)) {
            throw new IllegalArgumentException(
                    "Membership quota rollback user is not in the loadtest allowlist.");
        }
    }

    /** 单次武装只保存白名单内部用户 ID 和单调截止，不保存 Token、请求正文或幂等键。 */
    private record ArmedRollback(long userId, long expiresAtNanos) {

        private boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0L;
        }
    }
}
