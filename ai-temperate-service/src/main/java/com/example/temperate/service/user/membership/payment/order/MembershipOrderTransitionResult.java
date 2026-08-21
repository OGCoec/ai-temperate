package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;

/**
 * 该结果是来返回一次 Redis 订单状态迁移的业务归类、当前状态和单调版本，不把 Lua 内部编码泄漏给调用方。
 */
public record MembershipOrderTransitionResult(
        MembershipOrderTransitionOutcome outcome,
        MembershipOrderStatus status,
        long stateVersion) {

    public boolean applied() {
        return outcome == MembershipOrderTransitionOutcome.APPLIED;
    }
}
