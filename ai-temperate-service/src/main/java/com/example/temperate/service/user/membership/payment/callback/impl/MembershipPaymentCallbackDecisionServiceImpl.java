package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecision;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentCallbackDecisionService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来执行确定性软关单裁决：硬截止始终等于 expiresAt 加 closingDuration，不受消费延迟影响。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentCallbackDecisionServiceImpl
        implements MembershipPaymentCallbackDecisionService {

    private final MembershipPaymentProperties properties;

    public MembershipPaymentCallbackDecisionServiceImpl(
            MembershipPaymentProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    /**
     * CANCELLED/CLOSED 的首次事实转退款条件；PAID 后续通知应先由唯一性拦截，这里只保留无副作用拒绝兜底。
     */
    @Override
    public MembershipPaymentCallbackDecision decide(
            MembershipOrderSnapshot order,
            PaymentCallbackSnapshot callback) {
        MembershipOrderSnapshot current = Objects.requireNonNull(order);
        PaymentCallbackSnapshot payment = Objects.requireNonNull(callback);
        if (current.status() == MembershipOrderStatus.CANCELLED
                || current.status() == MembershipOrderStatus.CLOSED) {
            return refund();
        }
        if (current.status() == MembershipOrderStatus.PAID) {
            return direct(MembershipPaymentCallbackResolution.REJECTED);
        }
        if (current.status() != MembershipOrderStatus.PENDING_PAYMENT
                && current.status() != MembershipOrderStatus.CLOSING) {
            return direct(MembershipPaymentCallbackResolution.REJECTED);
        }
        // 回调事实已经先由数据库唯一约束保存；这里再按订单快照裁决金额和支付方式，失败时只写 REJECTED，绝不推进 PAID。
        if (current.payAmountYuan().compareTo(payment.paidAmountYuan()) != 0
                || !current.payType().equals(payment.payType())) {
            return direct(MembershipPaymentCallbackResolution.REJECTED);
        }

        OffsetDateTime startedAt = current.paymentStartedAt();
        if (startedAt == null
                || !startedAt.isBefore(current.expiresAt())
                || payment.paidAt().isBefore(startedAt)
                || payment.paidAt().isAfter(payment.receivedAt())) {
            return direct(MembershipPaymentCallbackResolution.REJECTED);
        }
        OffsetDateTime hardCloseAt = current.expiresAt().plus(properties.closingDuration());
        if (!payment.receivedAt().isBefore(hardCloseAt)) {
            return refund();
        }
        return new MembershipPaymentCallbackDecision(
                MembershipPaymentCallbackResolution.APPLIED,
                true,
                false);
    }

    private static MembershipPaymentCallbackDecision direct(
            MembershipPaymentCallbackResolution resolution) {
        return new MembershipPaymentCallbackDecision(resolution, false, false);
    }

    private static MembershipPaymentCallbackDecision refund() {
        return new MembershipPaymentCallbackDecision(
                MembershipPaymentCallbackResolution.REFUND_REQUIRED,
                false,
                true);
    }
}
