package com.example.temperate.service.user.membership.payment.entitlement;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来把一个已由 Redis 原子迁移为 PAID 的订单快照与其回调裁决绑定，供 PostgreSQL 权益事务恢复或首次发放。
 */
public record MembershipPaymentEntitlementCommand(
        String callbackId,
        MembershipOrderSnapshot paidOrder,
        OffsetDateTime resolvedAt) {

    public MembershipPaymentEntitlementCommand {
        new PaymentCallbackRedisId(callbackId);
        paidOrder = Objects.requireNonNull(paidOrder, "paidOrder must not be null");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        if (paidOrder.status() != MembershipOrderStatus.PAID
                || paidOrder.providerTradeNo() == null
                || paidOrder.paidAt() == null) {
            throw new IllegalArgumentException(
                    "Paid membership entitlement order is incomplete.");
        }
    }
}
