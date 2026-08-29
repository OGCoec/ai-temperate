package com.example.temperate.model.user.membership.payment;

/**
 * 该枚举是来标识当前环境启用的会员支付提供方；简化方案不落库，切换环境前必须先清理旧非终态订单。
 */
public enum PaymentProviderType {
    LOCAL_SIMULATOR,
    BAR
}
