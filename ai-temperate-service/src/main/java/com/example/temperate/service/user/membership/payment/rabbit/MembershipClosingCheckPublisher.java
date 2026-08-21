package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.Duration;

/**
 * 该发布契约是来发送下一段会员软关闭检查消息，并只在 RabbitMQ Publisher Confirm ACK 后返回成功。
 */
public interface MembershipClosingCheckPublisher {

    void publishNext(
            String orderId,
            int stageIndex,
            int terminalRetryCount,
            Duration delay);
}
