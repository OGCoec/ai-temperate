package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.Duration;

/**
 * 该发布契约是来发送下一段会员待支付检查消息，并只在 RabbitMQ Publisher Confirm ACK 后返回成功。
 */
public interface MembershipPaymentCheckPublisher {

    void publishNext(String orderId, int stageIndex, Duration delay);
}
