package com.example.temperate.service.user.membership.purchase;

/**
 * 该枚举是来表达一次会员等级请求属于首次购买、合法升级还是被规则拒绝。
 */
public enum MembershipTransitionType {
    NEW_PURCHASE,
    UPGRADE,
    REJECTED
}
