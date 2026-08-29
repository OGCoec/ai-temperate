package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来验证数据库事务外层计时在成功和异常路径都记录一次，并保持原返回值与异常传播不变。
 */
final class MembershipPaymentDatabaseTransactionTimingAspectTest {

    @Test
    void recordsSuccessfulTransactionOutsideTheTransactionalInvocation() throws Throwable {
        MembershipPaymentTimingRecorder recorder = mock(MembershipPaymentTimingRecorder.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Object result = new Object();
        when(joinPoint.proceed()).thenReturn(result);

        Object actual = new MembershipPaymentDatabaseTransactionTimingAspect(recorder)
                .observe(joinPoint);

        org.assertj.core.api.Assertions.assertThat(actual).isSameAs(result);
        verify(recorder).recordDatabaseTransaction(anyLong(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void recordsFailedTransactionAndRethrowsTheOriginalFailure() throws Throwable {
        MembershipPaymentTimingRecorder recorder = mock(MembershipPaymentTimingRecorder.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        IllegalStateException failure = new IllegalStateException("test");
        when(joinPoint.proceed()).thenThrow(failure);

        assertThatThrownBy(() -> new MembershipPaymentDatabaseTransactionTimingAspect(recorder)
                        .observe(joinPoint))
                .isSameAs(failure);
        verify(recorder).recordDatabaseTransaction(anyLong(), org.mockito.ArgumentMatchers.eq(false));
    }
}
