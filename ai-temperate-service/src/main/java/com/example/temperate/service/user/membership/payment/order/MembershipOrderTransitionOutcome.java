package com.example.temperate.service.user.membership.payment.order;

/**
 * 该枚举是来归类 Redis 会员订单状态迁移结果，使调用方区分有效迁移、幂等重放、晚到支付和受控拒绝。
 */
public enum MembershipOrderTransitionOutcome {
    APPLIED,
    ALREADY_APPLIED,
    LATE_TERMINAL,
    NOT_ALLOWED,
    TOO_EARLY,
    MISSING,
    PROVIDER_TRADE_CONFLICT,
    CALLBACK_IN_PROGRESS,
    PROVIDER_STATUS_UNSAFE,
    AMOUNT_MISMATCH
}
