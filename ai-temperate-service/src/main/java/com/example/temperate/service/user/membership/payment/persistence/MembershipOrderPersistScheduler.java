package com.example.temperate.service.user.membership.payment.persistence;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentSchedulingConfiguration;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkerTrigger;
import com.example.temperate.service.user.membership.payment.worker.MembershipPaymentWorkType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 该调度器是来每五秒触发一次订单脏版本刷盘轮次，不在调度入口实现锁、领取或数据库逻辑。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderPersistScheduler {

    private final MembershipPaymentWorkerTrigger workerTrigger;

    public MembershipOrderPersistScheduler(
            MembershipPaymentWorkerTrigger workerTrigger) {
        this.workerTrigger = java.util.Objects.requireNonNull(workerTrigger);
    }

    @Scheduled(
            fixedDelayString =
                    "${app.membership-payment.order-persist.flush-interval-millis:5000}",
            scheduler = MembershipPaymentSchedulingConfiguration.ORDER_PERSIST_TASK_SCHEDULER)
    public void flush() {
        workerTrigger.signal(MembershipPaymentWorkType.ORDER_PERSIST);
    }
}
