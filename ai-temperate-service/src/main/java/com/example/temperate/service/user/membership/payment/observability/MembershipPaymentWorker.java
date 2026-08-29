package com.example.temperate.service.user.membership.payment.observability;

/**
 * 该枚举是来标识会员支付两个有界后台 Worker，供低基数指标和本机压测证据稳定区分运行来源。
 */
public enum MembershipPaymentWorker {
    CALLBACK,
    ORDER_PERSIST
}
