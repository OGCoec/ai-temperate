package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import java.util.Objects;

/**
 * 该结果是来返回支付回调原子入队归类以及最终采用的回调 ID，重复请求会指向首次入队记录。
 */
public record PaymentCallbackEnqueueResult(
        PaymentCallbackEnqueueOutcome outcome,
        String callbackId) {

    public PaymentCallbackEnqueueResult {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        new PaymentCallbackRedisId(callbackId);
    }

    public boolean enqueued() {
        return outcome == PaymentCallbackEnqueueOutcome.ENQUEUED;
    }
}
