package com.example.temperate.service.user.membership.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerTrigger;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 该测试是来保证五秒兜底调度器只发送订单刷盘信号，不在调度线程直接获取锁或执行数据库写入。
 */
final class MembershipOrderPersistSchedulerTest {

    @Test
    void usesDedicatedOrderPersistenceTaskScheduler() throws NoSuchMethodException {
        Scheduled scheduled = MembershipOrderPersistScheduler.class
                .getDeclaredMethod("flush")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler())
                .isEqualTo("membershipPaymentOrderPersistTaskScheduler");
    }

    @Test
    void scheduledFallbackOnlySignalsOrderPersistenceWorker() {
        MembershipPaymentWorkerTrigger trigger = mock(MembershipPaymentWorkerTrigger.class);
        MembershipOrderPersistScheduler scheduler =
                new MembershipOrderPersistScheduler(trigger);

        scheduler.flush();
        verify(trigger).signal(MembershipPaymentWorkType.ORDER_PERSIST);
    }
}
