package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitConfirmCoordinator;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRabbitPublishBreakdown;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTimingRecorder;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该发送器是来保持既有发送契约，并把持久发布与逐消息 Confirm 等待委托给有界异步协调器。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class ConfirmedMembershipPaymentRabbitSender
        implements MembershipPaymentRabbitSender {

    private final MembershipPaymentRabbitConfirmCoordinator coordinator;
    private final MembershipPaymentTimingRecorder timingRecorder;

    public ConfirmedMembershipPaymentRabbitSender(
            MembershipPaymentRabbitConfirmCoordinator coordinator,
            MembershipPaymentTimingRecorder timingRecorder) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.timingRecorder = Objects.requireNonNull(timingRecorder);
    }

    @Override
    public void send(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Duration delay) {
        MembershipPaymentRabbitPublishBreakdown breakdown =
                coordinator.publishAndAwait(exchange, routingKey, envelope, delay);
        timingRecorder.recordRabbitPublishBreakdown(breakdown);
    }
}
