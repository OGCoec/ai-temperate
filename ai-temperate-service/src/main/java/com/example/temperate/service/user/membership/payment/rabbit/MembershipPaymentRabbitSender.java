package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.Duration;

/**
 * 该发送契约是来统一发布带 Confirm、持久化和有界延迟的会员支付 RabbitMQ 信封，不允许调用方降低可靠性设置。
 */
public interface MembershipPaymentRabbitSender {

    void send(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Duration delay);
}
