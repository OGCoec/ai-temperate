package com.example.temperate.service.user.membership.payment.worker.impl;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentSchedulingConfiguration;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkAvailableEvent;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerOutcome;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerRunResult;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerTrigger;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 该实现是来为两类 Worker 提供独立 single-flight drain；高水位最多连续四轮，随后让出单线程调度器再排队。
 *
 * <p>信号可以丢失或重复，因为每次执行都重新从 Redis ZSET 领取；FAILED 不立即自旋，五秒兜底负责后续恢复。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentWorkerTriggerImpl
        implements MembershipPaymentWorkerTrigger {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MembershipPaymentWorkerTriggerImpl.class);
    private static final int MAXIMUM_CONSECUTIVE_RUNS = 4;

    private final PaymentCallbackBatchService callbackService;
    private final MembershipOrderBatchPersistenceService orderPersistService;
    private final MembershipPaymentLoadtestFaultGate faultGate;
    private final Map<MembershipPaymentWorkType, WorkerState> states;

    public MembershipPaymentWorkerTriggerImpl(
            PaymentCallbackBatchService callbackService,
            MembershipOrderBatchPersistenceService orderPersistService,
            MembershipPaymentLoadtestFaultGate faultGate,
            @Qualifier(MembershipPaymentSchedulingConfiguration.CALLBACK_TASK_SCHEDULER)
                    TaskScheduler callbackScheduler,
            @Qualifier(MembershipPaymentSchedulingConfiguration.ORDER_PERSIST_TASK_SCHEDULER)
                    TaskScheduler orderPersistScheduler) {
        this.callbackService = Objects.requireNonNull(callbackService);
        this.orderPersistService = Objects.requireNonNull(orderPersistService);
        this.faultGate = Objects.requireNonNull(faultGate);
        EnumMap<MembershipPaymentWorkType, WorkerState> registered =
                new EnumMap<>(MembershipPaymentWorkType.class);
        registered.put(
                MembershipPaymentWorkType.CALLBACK,
                new WorkerState(Objects.requireNonNull(callbackScheduler)));
        registered.put(
                MembershipPaymentWorkType.ORDER_PERSIST,
                new WorkerState(Objects.requireNonNull(orderPersistScheduler)));
        this.states = Map.copyOf(registered);
    }

    @EventListener
    public void onWorkAvailable(MembershipPaymentWorkAvailableEvent event) {
        signal(Objects.requireNonNull(event).type());
    }

    @Override
    public void signal(MembershipPaymentWorkType type) {
        WorkerState state = states.get(Objects.requireNonNull(type));
        state.rerunRequested().set(true);
        scheduleIfIdle(type, state);
    }

    private void scheduleIfIdle(MembershipPaymentWorkType type, WorkerState state) {
        if (!state.running().compareAndSet(false, true)) {
            return;
        }
        try {
            state.scheduler().schedule(
                    () -> drain(type, state),
                    java.time.Instant.now());
        } catch (RuntimeException exception) {
            state.running().set(false);
            LOGGER.warn(
                    "Membership payment worker signal was rejected; type={} reason={}",
                    type,
                    exception.getClass().getSimpleName());
        }
    }

    private void drain(MembershipPaymentWorkType type, WorkerState state) {
        boolean failed = false;
        try (MembershipPaymentTraceContext ignored = MembershipPaymentTraceContext.open()) {
            for (int run = 0; run < MAXIMUM_CONSECUTIVE_RUNS; run++) {
                state.rerunRequested().set(false);
                MembershipPaymentWorkerRunResult result;
                try {
                    result = paused(type)
                            ? MembershipPaymentWorkerRunResult.empty(
                                    MembershipPaymentWorkerOutcome.PAUSED)
                            : service(type).get();
                } catch (RuntimeException exception) {
                    failed = true;
                    state.rerunRequested().set(false);
                    LOGGER.warn(
                            "Membership payment worker drain stopped; type={} reason={}",
                            type,
                            exception.getClass().getSimpleName());
                    break;
                }
                boolean continueImmediately =
                        result.outcome() == MembershipPaymentWorkerOutcome.CAPACITY
                                || state.rerunRequested().get();
                if (!continueImmediately
                        || result.outcome() == MembershipPaymentWorkerOutcome.RETRY
                        || result.outcome() == MembershipPaymentWorkerOutcome.LOCK_UNAVAILABLE
                        || result.outcome() == MembershipPaymentWorkerOutcome.PAUSED
                        || result.outcome() == MembershipPaymentWorkerOutcome.FAILED) {
                    break;
                }
                if (run == MAXIMUM_CONSECUTIVE_RUNS - 1) {
                    state.rerunRequested().set(true);
                }
            }
        } finally {
            state.running().set(false);
            if (!failed && state.rerunRequested().get()) {
                scheduleIfIdle(type, state);
            }
        }
    }

    private Supplier<MembershipPaymentWorkerRunResult> service(
            MembershipPaymentWorkType type) {
        return type == MembershipPaymentWorkType.CALLBACK
                ? callbackService::flushOneRun
                : orderPersistService::flushOneRun;
    }

    private boolean paused(MembershipPaymentWorkType type) {
        return type == MembershipPaymentWorkType.CALLBACK
                ? faultGate.callbackWorkerPaused()
                : faultGate.orderPersistenceWorkerPaused();
    }

    /** 每类 Worker 的运行位与补跑位互相独立，避免 Callback 与订单刷盘信号彼此覆盖。 */
    private record WorkerState(
            TaskScheduler scheduler,
            AtomicBoolean running,
            AtomicBoolean rerunRequested) {

        private WorkerState(TaskScheduler scheduler) {
            this(scheduler, new AtomicBoolean(), new AtomicBoolean());
        }
    }
}
