package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该服务是来处理一条 PENDING_PAYMENT 分段检查信封，中间阶段只续发延时消息，最终阶段才主动查询模拟平台。
 */
public interface MembershipPaymentCheckConsumerService {

    void process(MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope);
}
