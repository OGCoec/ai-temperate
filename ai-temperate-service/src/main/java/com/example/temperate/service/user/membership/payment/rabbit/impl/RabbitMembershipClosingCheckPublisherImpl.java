package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来构造带有限终态重试次数的 CLOSING 检查信封，并委托专用发送器等待 RabbitMQ Confirm。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RabbitMembershipClosingCheckPublisherImpl
        implements MembershipClosingCheckPublisher {

    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MembershipPaymentRabbitSender sender;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public RabbitMembershipClosingCheckPublisherImpl(
            MembershipPaymentRabbitSender sender,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            Clock clock) {
        this.sender = Objects.requireNonNull(sender);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void publishNext(
            String orderId,
            int stageIndex,
            int terminalRetryCount,
            Duration delay) {
        MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        base64UrlCodec.encode(idWorker.nextId()),
                        MembershipPaymentRabbitNames.CLOSING_EVENT,
                        MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                        MembershipPaymentTime.now(clock),
                        traceId(),
                        new MembershipClosingCheckMessage(
                                orderId, stageIndex, terminalRetryCount));
        sender.send(
                MembershipPaymentRabbitNames.CLOSING_EXCHANGE,
                MembershipPaymentRabbitNames.CLOSING_ROUTING_KEY,
                envelope,
                delay);
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || !SAFE_TRACE_ID.matcher(traceId).matches()
                ? UUID.randomUUID().toString()
                : traceId;
    }
}
