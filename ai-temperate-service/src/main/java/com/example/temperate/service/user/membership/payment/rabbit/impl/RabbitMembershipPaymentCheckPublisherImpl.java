package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
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
 * 该实现是来构造带完整可靠性元数据的 PENDING 检查信封，并委托专用发送器等待 RabbitMQ Confirm。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RabbitMembershipPaymentCheckPublisherImpl
        implements MembershipPaymentCheckPublisher {

    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MembershipPaymentRabbitSender sender;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public RabbitMembershipPaymentCheckPublisherImpl(
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
    public void publishNext(String orderId, int stageIndex, Duration delay) {
        MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        base64UrlCodec.encode(idWorker.nextId()),
                        MembershipPaymentRabbitNames.PAYMENT_EVENT,
                        MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        traceId(),
                        new MembershipPaymentCheckMessage(orderId, stageIndex));
        sender.send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
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
