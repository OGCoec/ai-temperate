package com.example.temperate.service.user.membership.payment.order;

/**
 * 该枚举是来标识本地关单终态的事实来源，区分平台确认与截止时间未确认；它不是订单状态，也不代表跨系统强一致。
 */
public enum MembershipClosingFinalizationSource {
    PROVIDER_CONFIRMED,
    TIMEOUT_UNCONFIRMED
}
