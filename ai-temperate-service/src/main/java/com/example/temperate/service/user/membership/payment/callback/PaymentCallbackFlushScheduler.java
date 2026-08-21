package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PaymentCallbackFlushScheduler.class);

    private final PaymentCallbackBatchService batchService;

    public PaymentCallbackFlushScheduler(PaymentCallbackBatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.membership-payment.callback.flush-interval-millis:5000}")
    public void flush() {
        try (MembershipPaymentTraceContext traceContext =
                MembershipPaymentTraceContext.open()) {
            try {
                batchService.flushOneRun();
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Membership payment callback flush stopped; traceId={} reason={}",
                        traceContext.traceId(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
