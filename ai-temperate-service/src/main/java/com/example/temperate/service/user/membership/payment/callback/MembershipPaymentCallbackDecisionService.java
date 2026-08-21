package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;

/**
 * 该服务是来根据支付发起事实、固定硬截止、当前订单状态和交易流水决定成功回调的最终处理方向。
 */
public interface MembershipPaymentCallbackDecisionService {

    MembershipPaymentCallbackDecision decide(
            MembershipOrderSnapshot order,
            PaymentCallbackSnapshot callback);
}
