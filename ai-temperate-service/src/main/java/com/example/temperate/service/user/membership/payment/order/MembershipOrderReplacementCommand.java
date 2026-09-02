package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来把 Redis 已裁决的旧订单终态及版本交给 PostgreSQL，并在同一事务中创建替换订单。
 */
public record MembershipOrderReplacementCommand(
        MembershipOrder candidate,
        byte[] replacedOrderId,
        MembershipOrderStatus terminalStatus,
        long terminalStateVersion,
        OffsetDateTime changedAt) {

    public MembershipOrderReplacementCommand {
        candidate = Objects.requireNonNull(candidate);
        replacedOrderId = Objects.requireNonNull(replacedOrderId).clone();
        terminalStatus = Objects.requireNonNull(terminalStatus);
        if (terminalStatus != MembershipOrderStatus.CANCELLED
                && terminalStatus != MembershipOrderStatus.CLOSED) {
            throw new IllegalArgumentException("Replacement terminal status is invalid.");
        }
        if (terminalStateVersion <= 0L) {
            throw new IllegalArgumentException("Replacement state version must be positive.");
        }
        changedAt = Objects.requireNonNull(changedAt);
    }

    @Override
    public byte[] replacedOrderId() {
        return replacedOrderId.clone();
    }
}
