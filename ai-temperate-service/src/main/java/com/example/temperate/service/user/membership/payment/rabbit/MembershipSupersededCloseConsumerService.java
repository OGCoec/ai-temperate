package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该服务是来关闭已被新订单替换的第三方支付单，并把查询到的已支付事实交回现有自动退款链。
 */
public interface MembershipSupersededCloseConsumerService {

    void process(MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope);
}
