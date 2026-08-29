package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRabbitPublishBreakdown;
import java.time.Duration;

/**
 * 该内部协调器是来有界并发提交 Rabbit 发布并精确等待各自 Confirm，调用方不能绕过持久化与 Return 校验。
 */
public interface MembershipPaymentRabbitConfirmCoordinator {

    MembershipPaymentRabbitPublishBreakdown publishAndAwait(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Duration delay);
}
