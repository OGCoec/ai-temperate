package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该精简视图是来承载支付发起前后必须复核的 Redis 实时状态，避免为了五个裁决字段读取完整订单 Hash。
 */
public record MembershipOrderRealtimeGuard(
        String orderId,
        long loginIdentityId,
        MembershipOrderStatus status,
        OffsetDateTime expiresAt,
        long stateVersion) {

    public MembershipOrderRealtimeGuard {
        new MembershipOrderRedisId(orderId);
        if (loginIdentityId <= 0L || stateVersion <= 0L) {
            throw new IllegalArgumentException("Membership order realtime guard is invalid.");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        expiresAt = MembershipPaymentTime.normalize(
                Objects.requireNonNull(expiresAt, "expiresAt must not be null"));
    }
}
