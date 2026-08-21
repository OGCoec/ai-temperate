package com.example.temperate.service.user.membership.payment.order;

import java.util.Objects;

/**
 * 该结果是来向 Web 层返回当前订单快照，并标明创建请求是否首次插入数据库以选择 201 或 200。
 */
public record MembershipOrderResult(
        MembershipOrderSnapshot snapshot,
        boolean created) {

    public MembershipOrderResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
