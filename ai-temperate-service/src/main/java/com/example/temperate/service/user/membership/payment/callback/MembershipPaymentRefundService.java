package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;

/**
 * 该服务是来在 REFUND_REQUIRED 裁决提交后执行一次 Provider 幂等全额退款，并返回不依赖异常正文的重试分类。
 */
public interface MembershipPaymentRefundService {

    PaymentRefundAttemptResult refund(PaymentRefundCommand command, int attemptNo);
}
