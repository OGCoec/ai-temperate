package com.example.temperate.service.user.membership.payment.observability;

/**
 * 该枚举是来定义会员支付允许进入日志和指标的固定业务入口，防止把动态方法名或订单信息写成监控标签。
 */
public enum MembershipPaymentOperation {
    ORDER_CREATE,
    ORDER_GET,
    ORDER_CANCEL,
    PAYMENT_ATTEMPT,
    RABBIT_PENDING,
    RABBIT_CLOSING,
    BAR_CALLBACK_RECEIVE,
    SIMULATED_CALLBACK_RECEIVE,
    CALLBACK_WORKER_BATCH
}
