package com.example.temperate.model.user.membership.payment;

/**
 * 该枚举是来表示一条已落库支付成功回调的最终裁决，并与数据库 CHECK 约束保持完全一致。
 */
public enum MembershipPaymentCallbackResolution {
    APPLIED,
    ALREADY_APPLIED,
    REFUND_REQUIRED,
    REJECTED
}
