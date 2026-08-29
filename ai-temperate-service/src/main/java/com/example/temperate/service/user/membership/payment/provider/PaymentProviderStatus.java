package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该枚举是来把不同支付提供方的订单状态归一化，供查询、关单和退款编排使用。
 */
public enum PaymentProviderStatus {
    PENDING,
    PAID,
    REFUNDED,
    FAILED,
    CLOSED,
    EXPIRED,
    UNKNOWN
}
