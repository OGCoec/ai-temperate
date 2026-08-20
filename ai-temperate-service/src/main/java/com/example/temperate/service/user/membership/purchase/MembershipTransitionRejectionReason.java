package com.example.temperate.service.user.membership.purchase;

/**
 * 该枚举是来为会员转换拒绝提供稳定原因，供未来购买入口映射受控业务错误。
 */
public enum MembershipTransitionRejectionReason {
    NONE,
    TARGET_FREE_NOT_PURCHASABLE,
    SAME_TIER_NOT_RENEWABLE,
    DOWNGRADE_NOT_ALLOWED,
    CROSS_CHAIN_NOT_ALLOWED,
    LOCKED_TIER_NOT_SWITCHABLE
}
