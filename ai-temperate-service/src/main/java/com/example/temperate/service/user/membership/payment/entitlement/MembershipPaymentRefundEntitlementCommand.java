package com.example.temperate.service.user.membership.payment.entitlement;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来在执行外部退款前，把订单权益与支付回调同时裁决为 REFUND_REQUIRED。
 */
public record MembershipPaymentRefundEntitlementCommand(
        String callbackId,
        String orderId,
        OffsetDateTime resolvedAt) {

    public MembershipPaymentRefundEntitlementCommand {
        new PaymentCallbackRedisId(callbackId);
        new MembershipOrderRedisId(orderId);
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
    }
}
