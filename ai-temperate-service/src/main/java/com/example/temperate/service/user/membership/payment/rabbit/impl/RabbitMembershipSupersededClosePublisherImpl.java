package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededCloseMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededClosePublisher;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来为被替换旧单构造持久 Rabbit 信封，并在 Publisher Confirm 成功后返回。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RabbitMembershipSupersededClosePublisherImpl
        implements MembershipSupersededClosePublisher {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MembershipPaymentRabbitSender sender;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public RabbitMembershipSupersededClosePublisherImpl(
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
    public void publish(String orderId, int retryCount, Duration delay) {
        MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope =
                new MembershipPaymentRabbitEnvelope<>(
                        base64UrlCodec.encode(idWorker.nextId()),
                        MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EVENT,
                        MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                        MembershipPaymentTime.now(clock),
                        traceId(),
                        new MembershipSupersededCloseMessage(orderId, retryCount));
        sender.send(
                MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EXCHANGE,
                MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_ROUTING_KEY,
                envelope,
                Objects.requireNonNull(delay));
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || !SAFE_TRACE_ID.matcher(value).matches()
                ? UUID.randomUUID().toString()
                : value;
    }
}
