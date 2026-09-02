package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.refund.PaymentRefundTerminalOutcome;
import java.util.Objects;

/**
 * 该消息是来持久保存需要人工处理的退款失败责任，只允许固定分类且不得触发新的外部退款请求。
 */
public record MembershipRefundTerminalFailureMessage(
        String callbackId,
        PaymentProviderType provider,
        PaymentRefundTerminalOutcome outcome,
        String safeReason,
        int attemptNo,
        int maxAttempts,
        boolean manualRequired) {

    public MembershipRefundTerminalFailureMessage {
        new PaymentCallbackRedisId(callbackId);
        provider = Objects.requireNonNull(provider);
        outcome = Objects.requireNonNull(outcome);
        if (safeReason == null || !safeReason.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("Membership refund terminal reason is invalid.");
        }
        if (attemptNo < 1 || attemptNo > 6 || maxAttempts != 6
                || attemptNo > maxAttempts || !manualRequired) {
            throw new IllegalArgumentException("Membership refund terminal metadata is invalid.");
        }
    }
}
