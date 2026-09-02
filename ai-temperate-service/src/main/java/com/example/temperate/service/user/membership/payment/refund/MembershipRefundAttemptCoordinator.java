package com.example.temperate.service.user.membership.payment.refund;

import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;

/**
 * 该协调服务是来统一执行初次与延迟退款尝试，并保证只有超时能够生成下一条外部退款请求消息。
 */
public interface MembershipRefundAttemptCoordinator {

    void processInitial(String callbackId, PaymentRefundCommand command);

    void processRetry(
            MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope);
}
