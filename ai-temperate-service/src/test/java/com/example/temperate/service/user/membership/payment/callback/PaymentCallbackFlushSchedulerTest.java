package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerTrigger;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 该测试是来保证五秒兜底调度器只发送 Callback 工作信号，不在调度线程直接领取或处理回调。
 */
final class PaymentCallbackFlushSchedulerTest {

    @Test
    void usesDedicatedCallbackTaskScheduler() throws NoSuchMethodException {
        Scheduled scheduled = PaymentCallbackFlushScheduler.class
                .getDeclaredMethod("flush")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler())
                .isEqualTo("membershipPaymentCallbackTaskScheduler");
    }

    @Test
    void scheduledFallbackOnlySignalsCallbackWorker() {
        MembershipPaymentWorkerTrigger trigger = mock(MembershipPaymentWorkerTrigger.class);
        PaymentCallbackFlushScheduler scheduler =
                new PaymentCallbackFlushScheduler(trigger);

        scheduler.flush();
        verify(trigger).signal(MembershipPaymentWorkType.CALLBACK);
    }
}
