package com.example.temperate.service.user.membership.payment.refund;

import java.util.Objects;

/**
 * 该决策是来返回 Redis 原子状态机的低敏下一步，不携带金额、第三方流水、PID 或签名。
 */
public record PaymentRefundCoordinationDecision(
        PaymentRefundCoordinationAction action,
        int attemptNo,
        String messageId,
        int nextAttemptNo,
        PaymentRefundTerminalOutcome terminalOutcome,
        String safeReason) {

    public PaymentRefundCoordinationDecision {
        action = Objects.requireNonNull(action);
        if (attemptNo < 0 || attemptNo > 6 || nextAttemptNo < 0 || nextAttemptNo > 6) {
            throw new IllegalArgumentException("Payment refund coordination attempt is invalid.");
        }
        if (safeReason != null && !safeReason.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("Payment refund coordination reason is invalid.");
        }
    }
}
