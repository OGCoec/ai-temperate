package com.example.temperate.service.user.membership.payment.worker.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerOutcome;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * 该测试是来约束 Worker 的 single-flight、四轮让出和 Redis 容量未排空时的有界续跑行为。
 */
final class MembershipPaymentWorkerTriggerImplTest {

    @Test
    void repeatedSignalsStartOneDrainAndCapacityYieldsAfterFourRuns() {
        PaymentCallbackBatchService callbackService = mock(PaymentCallbackBatchService.class);
        MembershipOrderBatchPersistenceService orderService =
                mock(MembershipOrderBatchPersistenceService.class);
        MembershipPaymentLoadtestFaultGate faultGate =
                mock(MembershipPaymentLoadtestFaultGate.class);
        Deque<Runnable> callbackTasks = new ArrayDeque<>();
        TaskScheduler callbackScheduler = queuedScheduler(callbackTasks);
        TaskScheduler orderScheduler = queuedScheduler(new ArrayDeque<>());
        when(callbackService.flushOneRun())
                .thenReturn(new MembershipPaymentWorkerRunResult(
                        1, 100, MembershipPaymentWorkerOutcome.CAPACITY))
                .thenReturn(new MembershipPaymentWorkerRunResult(
                        1, 100, MembershipPaymentWorkerOutcome.CAPACITY))
                .thenReturn(new MembershipPaymentWorkerRunResult(
                        1, 100, MembershipPaymentWorkerOutcome.CAPACITY))
                .thenReturn(new MembershipPaymentWorkerRunResult(
                        1, 100, MembershipPaymentWorkerOutcome.CAPACITY))
                .thenReturn(MembershipPaymentWorkerRunResult.empty(
                        MembershipPaymentWorkerOutcome.DRAINED));
        MembershipPaymentWorkerTriggerImpl trigger = new MembershipPaymentWorkerTriggerImpl(
                callbackService,
                orderService,
                faultGate,
                callbackScheduler,
                orderScheduler);

        trigger.signal(MembershipPaymentWorkType.CALLBACK);
        trigger.signal(MembershipPaymentWorkType.CALLBACK);
        trigger.signal(MembershipPaymentWorkType.CALLBACK);
        assertThat(callbackTasks).hasSize(1);

        callbackTasks.removeFirst().run();
        assertThat(callbackTasks).hasSize(1);
        verify(callbackService, times(4)).flushOneRun();

        callbackTasks.removeFirst().run();
        assertThat(callbackTasks).isEmpty();
        verify(callbackService, times(5)).flushOneRun();
    }

    private static TaskScheduler queuedScheduler(Deque<Runnable> tasks) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    tasks.addLast(invocation.getArgument(0));
                    return null;
                });
        return scheduler;
    }
}
