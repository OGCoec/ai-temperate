package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;
import com.example.temperate.service.user.membership.payment.refund.MembershipRefundAttemptCoordinator;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来保持 Rabbit 监听器只负责编排 ACK/NACK，并把退款状态机交给支付域协调服务。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipRefundRetryConsumerServiceImpl
        implements MembershipRefundRetryConsumerService {

    private final MembershipRefundAttemptCoordinator coordinator;

    public MembershipRefundRetryConsumerServiceImpl(
            MembershipRefundAttemptCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator);
    }

    @Override
    public void process(
            MembershipPaymentRabbitEnvelope<MembershipRefundRetryMessage> envelope) {
        coordinator.processRetry(Objects.requireNonNull(envelope));
    }
}
