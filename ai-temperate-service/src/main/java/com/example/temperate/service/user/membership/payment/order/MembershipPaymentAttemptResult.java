package com.example.temperate.service.user.membership.payment.order;

import java.util.Objects;

/**
 * 该结果是来同时返回支付发起后的订单快照以及本次请求是否首次写入，供 Web 层稳定选择 201 或 200。
 */
public record MembershipPaymentAttemptResult(
        MembershipOrderSnapshot snapshot,
        boolean started) {

    public MembershipPaymentAttemptResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
