package com.example.temperate.model.user.membership.payment;

/**
 * 该枚举是来标识会员支付提供方；类型不新增落库字段，新外部订单通过既有第三方流水号前缀路由。
 */
public enum PaymentProviderType {
    LOCAL_SIMULATOR,
    BAR,
    LIUHAO
}
