package com.example.temperate.service.user.membership.payment.provider;

/**
 * 该枚举是来表达模拟支付方主动查询的三值结果，UNKNOWN 必须重试或进入死信，禁止直接关单。
 */
public enum SimulatedPaymentProviderStatus {
    UNPAID,
    PAID,
    UNKNOWN
}
