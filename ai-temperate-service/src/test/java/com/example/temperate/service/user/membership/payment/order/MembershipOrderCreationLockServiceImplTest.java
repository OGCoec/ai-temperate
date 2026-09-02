package com.example.temperate.service.user.membership.payment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipOrderCreationLockServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 该单元测试是来约束会员订单强制替换必须持有用户级 Redisson 看门狗锁，且协调失败时不能执行资金状态变更。
 */
final class MembershipOrderCreationLockServiceImplTest {

    @Test
    void acquiredLockRunsActionAndReleasesOnlyOwnedLock() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RedisKeyFactory keys = new RedisKeyFactory("test");
        when(redisson.getLock(keys.membershipOrderCreationLockKey(17L))).thenReturn(lock);
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MembershipOrderCreationLockService service = new MembershipOrderCreationLockServiceImpl(
                redisson, keys, new SimpleMeterRegistry());

        String value = service.execute(17L, () -> "created");

        assertThat(value).isEqualTo("created");
        verify(lock).unlock();
    }

    @Test
    void unavailableRedisNeverRunsReplacementAction() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisKeyFactory keys = new RedisKeyFactory("test");
        when(redisson.getLock(keys.membershipOrderCreationLockKey(17L)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        AtomicBoolean invoked = new AtomicBoolean();
        MembershipOrderCreationLockService service = new MembershipOrderCreationLockServiceImpl(
                redisson, keys, new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.execute(17L, () -> {
                    invoked.set(true);
                    return "unexpected";
                }))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE));
        assertThat(invoked).isFalse();
        verify(redisson, never()).shutdown();
    }

    @Test
    void boundedContentionNeverRunsASecondCreationAction() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RedisKeyFactory keys = new RedisKeyFactory("test");
        when(redisson.getLock(keys.membershipOrderCreationLockKey(17L))).thenReturn(lock);
        when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(false);
        AtomicBoolean invoked = new AtomicBoolean();
        MembershipOrderCreationLockService service = new MembershipOrderCreationLockServiceImpl(
                redisson, keys, new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.execute(17L, () -> {
                    invoked.set(true);
                    return "unexpected";
                }))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE));
        assertThat(invoked).isFalse();
        verify(lock, never()).unlock();
    }
}
