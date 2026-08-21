package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import java.util.Objects;

/**
 * 该决策是来区分回调应继续推进 PAID、直接完成审计或触发退款条件，避免状态机调用方重复解释时间边界。
 */
public record MembershipPaymentCallbackDecision(
        MembershipPaymentCallbackResolution resolution,
        boolean applyPayment,
        boolean refundRequired) {

    public MembershipPaymentCallbackDecision {
        resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        if (applyPayment && resolution != MembershipPaymentCallbackResolution.APPLIED) {
            throw new IllegalArgumentException("Only APPLIED may enter the paid state machine.");
        }
        if (refundRequired
                != (resolution == MembershipPaymentCallbackResolution.REFUND_REQUIRED)) {
            throw new IllegalArgumentException("Refund flag must match the resolution.");
        }
    }
}
