package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;

/**
 * 该消息是来只携带退款回调定位和单调尝试次数，金额、流水、PID、退款号及签名必须从消息中排除。
 */
public record MembershipRefundRetryMessage(
        String callbackId,
        int attemptNo,
        int maxAttempts) {

    public MembershipRefundRetryMessage {
        new PaymentCallbackRedisId(callbackId);
        if (attemptNo < 2 || attemptNo > 6 || maxAttempts != 6
                || attemptNo > maxAttempts) {
            throw new IllegalArgumentException("Membership refund retry attempt is invalid.");
        }
    }
}
