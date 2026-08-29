package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import java.util.Objects;

/**
 * 该命令是来在 REJECTED 回调恢复 MQ 时间链前精确释放本次 callback marker，同时保留 processing claim 作为崩溃恢复依据。
 */
public record MembershipPaymentRejectedCallbackReleaseCommand(
        PaymentCallbackClaim claim,
        String orderId) {

    public MembershipPaymentRejectedCallbackReleaseCommand {
        claim = Objects.requireNonNull(claim, "claim must not be null");
        orderId = new MembershipOrderRedisId(orderId).value();
    }
}
