package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundRequiredTrigger;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来用固定事件名和低基数状态记录退款条件，不包含订单、用户、回调或交易流水标识。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentRefundRequiredTriggerImpl
        implements MembershipPaymentRefundRequiredTrigger {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipPaymentRefundRequiredTriggerImpl.class);
    private static final String EVENT_NAME = "MEMBERSHIP_PAYMENT_REFUND_REQUIRED";

    private final MembershipPaymentMetrics metrics;

    public MembershipPaymentRefundRequiredTriggerImpl(MembershipPaymentMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void trigger(MembershipOrderStatus sourceStatus) {
        MembershipOrderStatus status = Objects.requireNonNull(sourceStatus);
        LOGGER.warn(
                "{} traceId={} status={}",
                EVENT_NAME,
                MembershipPaymentTraceContext.currentTraceId(),
                status);
        metrics.refundRequired();
    }
}
