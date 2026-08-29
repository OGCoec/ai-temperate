package com.example.temperate.service.user.membership.payment.order;

/**
 * 该服务是来记录首次支付发起事实、调用当前 Provider 创建模拟支付页面，并返回可幂等重放的结果。
 */
public interface MembershipPaymentAttemptService {

    MembershipPaymentAttemptResult start(long loginIdentityId, byte[] orderId);
}
