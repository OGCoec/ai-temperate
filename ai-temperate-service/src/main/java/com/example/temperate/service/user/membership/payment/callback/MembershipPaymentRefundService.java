package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;

/**
 * 该服务是来在回调 REFUND_REQUIRED 裁决提交后执行当前 Provider 的幂等全额退款，不保存本地退款流水。
 */
public interface MembershipPaymentRefundService {

    void refund(PaymentRefundCommand command);
}
