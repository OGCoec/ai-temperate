package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该服务是来处理一条 CLOSING 分段检查信封，只有最终确认 UNPAID 且无 marker 时才允许 Lua 关单。
 */
public interface MembershipClosingCheckConsumerService {

    void process(MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope);
}
