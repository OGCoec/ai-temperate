package com.example.temperate.service.user.membership.payment.persistence;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipOrderPersistScheduler.class);

    private final MembershipOrderBatchPersistenceService batchService;

    public MembershipOrderPersistScheduler(
            MembershipOrderBatchPersistenceService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.membership-payment.order-persist.flush-interval-millis:5000}")
    public void flush() {
        try (MembershipPaymentTraceContext traceContext =
                MembershipPaymentTraceContext.open()) {
            try {
                batchService.flushOneRun();
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Membership order persistence flush stopped; traceId={} reason={}",
                        traceContext.traceId(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
