package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import java.util.Objects;

/**
 * 该结果是来同时返回支付发起后的订单快照、201/200 判定、Provider 和本次可空的短时提交描述。
 */
public record MembershipPaymentAttemptResult(
        MembershipOrderSnapshot snapshot,
        boolean started,
        PaymentProviderType provider,
        PaymentCheckoutSubmission checkoutSubmission) {

    public MembershipPaymentAttemptResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        provider = Objects.requireNonNull(provider, "provider must not be null");
    }
}
