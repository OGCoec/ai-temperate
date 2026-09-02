package com.example.temperate.service.user.membership.payment.rabbit;

import java.time.Duration;

/**
 * 该发布契约是来为退款延迟和人工终态消息提供新 ID，并等待每次持久发布的 Broker Confirm。
 */
public interface MembershipRefundMessagePublisher {

    String newMessageId();

    void publishRetry(
            String messageId,
            MembershipRefundRetryMessage message,
            Duration delay);

    void publishTerminal(
            String messageId,
            MembershipRefundTerminalFailureMessage message);
}
