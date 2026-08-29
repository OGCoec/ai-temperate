package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentSchedulingConfiguration;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerTrigger;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 该调度器是来每五秒触发一次有界回调收敛轮次，不在调度入口实现领取、数据库或状态机逻辑。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class PaymentCallbackFlushScheduler {

    private final MembershipPaymentWorkerTrigger workerTrigger;

    public PaymentCallbackFlushScheduler(
            MembershipPaymentWorkerTrigger workerTrigger) {
        this.workerTrigger = java.util.Objects.requireNonNull(workerTrigger);
    }

    @Scheduled(
            fixedDelayString =
                    "${app.membership-payment.callback.flush-interval-millis:5000}",
            scheduler = MembershipPaymentSchedulingConfiguration.CALLBACK_TASK_SCHEDULER)
    public void flush() {
        workerTrigger.signal(MembershipPaymentWorkType.CALLBACK);
    }
}
