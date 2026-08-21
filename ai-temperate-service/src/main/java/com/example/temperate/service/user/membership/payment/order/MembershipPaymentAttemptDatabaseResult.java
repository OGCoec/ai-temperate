package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import java.util.Objects;

/**
 * 该结果是来承载 PostgreSQL 对支付首次发起或有效期内幂等重放的最终裁决，不包含 Redis 状态。
 */
public record MembershipPaymentAttemptDatabaseResult(
        MembershipOrder order,
        boolean started) {

    public MembershipPaymentAttemptDatabaseResult {
        order = Objects.requireNonNull(order, "order must not be null");
    }
}
