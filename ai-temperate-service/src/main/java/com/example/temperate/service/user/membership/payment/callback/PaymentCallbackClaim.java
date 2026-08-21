package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;

/**
 * 该领取凭证是来绑定支付回调 ID 与 processing ZSet 的精确领取时间，防止超时旧 Worker 完成新租约。
 */
public record PaymentCallbackClaim(String callbackId, long claimedAtEpochMillis) {

    public PaymentCallbackClaim {
        new PaymentCallbackRedisId(callbackId);
        if (claimedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("Payment callback claim time must be positive.");
        }
    }
}
