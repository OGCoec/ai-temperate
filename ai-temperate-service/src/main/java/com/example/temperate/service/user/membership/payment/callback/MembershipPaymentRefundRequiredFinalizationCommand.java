package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来把退款裁决对应的 callback claim、订单、第三方流水和硬关单时间交给 Redis 终态收敛。
 *
 * <p>resolvedAt 同时作为订单真实变更时间与脏队列分值来源，保证 PostgreSQL 最终投影与回调裁决使用同一时间事实。</p>
 */
public record MembershipPaymentRefundRequiredFinalizationCommand(
        PaymentCallbackClaim claim,
        String orderId,
        String providerTradeNo,
        OffsetDateTime hardCloseAt,
        OffsetDateTime resolvedAt) {

    public MembershipPaymentRefundRequiredFinalizationCommand {
        claim = Objects.requireNonNull(claim, "claim must not be null");
        orderId = new MembershipOrderRedisId(orderId).value();
        providerTradeNo = Objects.requireNonNull(
                providerTradeNo, "providerTradeNo must not be null");
        if (providerTradeNo.isBlank() || !providerTradeNo.equals(providerTradeNo.trim())) {
            throw new IllegalArgumentException(
                    "providerTradeNo must be non-blank and already trimmed");
        }
        hardCloseAt = Objects.requireNonNull(hardCloseAt, "hardCloseAt must not be null");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
    }
}
