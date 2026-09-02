package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该消费服务是来处理一条已经到期的退款超时重试消息，重复或旧消息必须无副作用返回。
 */
public interface MembershipRefundRetryConsumerService {

    void process(MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope);
}
