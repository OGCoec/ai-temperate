package com.example.temperate.service.user.membership.payment.order;

/**
 * 该服务是来为当前用户在会员订单有效期内记录首次支付发起事实，并返回可幂等重放的原始发起时间。
 */
public interface MembershipPaymentAttemptService {

    MembershipPaymentAttemptResult start(long loginIdentityId, byte[] orderId);
}
