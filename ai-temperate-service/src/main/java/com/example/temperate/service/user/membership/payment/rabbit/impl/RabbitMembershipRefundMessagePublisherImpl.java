package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundMessagePublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundRetryMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipRefundTerminalFailureMessage;
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
 * 该实现是来生成退款消息 ID、构造不含支付敏感事实的信封，并同步等待持久 Rabbit 发布确认。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class RabbitMembershipRefundMessagePublisherImpl
        implements MembershipRefundMessagePublisher {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MembershipPaymentRabbitSender sender;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec idCodec;
    private final Clock clock;

    public RabbitMembershipRefundMessagePublisherImpl(
            MembershipPaymentRabbitSender sender,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec idCodec,
            Clock clock) {
        this.sender = Objects.requireNonNull(sender);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String newMessageId() {
        return idCodec.encode(idWorker.nextId());
    }

    @Override
    public void publishRetry(
            String messageId,
            MembershipRefundRetryMessage message,
            Duration delay) {
        sender.send(
                MembershipPaymentRabbitNames.REFUND_RETRY_EXCHANGE,
                MembershipPaymentRabbitNames.REFUND_RETRY_ROUTING_KEY,
                envelope(messageId, MembershipPaymentRabbitNames.REFUND_RETRY_EVENT, message),
                Objects.requireNonNull(delay));
    }

    @Override
    public void publishTerminal(
            String messageId,
            MembershipRefundTerminalFailureMessage message) {
        sender.send(
                MembershipPaymentRabbitNames.REFUND_TERMINAL_EXCHANGE,
                MembershipPaymentRabbitNames.REFUND_TERMINAL_ROUTING_KEY,
                envelope(messageId, MembershipPaymentRabbitNames.REFUND_TERMINAL_EVENT, message),
                Duration.ZERO);
    }

    private <T> MembershipPaymentRabbitEnvelope<T> envelope(
            String messageId, String eventType, T payload) {
        return new MembershipPaymentRabbitEnvelope<>(
                messageId,
                eventType,
                MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                MembershipPaymentTime.now(clock),
                traceId(),
                payload);
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || !SAFE_TRACE_ID.matcher(value).matches()
                ? UUID.randomUUID().toString()
                : value;
    }
}
