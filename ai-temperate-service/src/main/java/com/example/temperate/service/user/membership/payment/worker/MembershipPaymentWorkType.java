package com.example.temperate.service.user.membership.payment.worker;

/**
 * 该枚举是来区分需要主动唤醒的回调收敛与订单脏版本刷盘工作。
 */
public enum MembershipPaymentWorkType {
    CALLBACK,
    ORDER_PERSIST
}
