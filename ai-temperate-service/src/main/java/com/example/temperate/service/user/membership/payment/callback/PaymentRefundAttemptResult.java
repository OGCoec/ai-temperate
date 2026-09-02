package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.util.Objects;

/**
 * 该结果是来把退款 Provider 的低基数裁决交给回调或延迟消费者，不携带异常正文、订单、流水或金额。
 */
public record PaymentRefundAttemptResult(
        PaymentRefundAttemptOutcome outcome,
        String safeReason,
        PaymentProviderType provider,
        int attemptNo) {

    public PaymentRefundAttemptResult {
        outcome = Objects.requireNonNull(outcome);
        provider = Objects.requireNonNull(provider);
        if (safeReason == null || !safeReason.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("Payment refund reason is invalid.");
        }
        if (attemptNo < 1 || attemptNo > 6) {
            throw new IllegalArgumentException("Payment refund attempt is invalid.");
        }
    }
}
