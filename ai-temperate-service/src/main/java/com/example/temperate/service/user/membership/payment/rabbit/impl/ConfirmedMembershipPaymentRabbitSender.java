package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该发送器是来通过会员支付专用 RabbitTemplate 发布持久延时消息，并同步等待 Confirm ACK 与 Return 检查。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class ConfirmedMembershipPaymentRabbitSender
        implements MembershipPaymentRabbitSender {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;

    public ConfirmedMembershipPaymentRabbitSender(
            @Qualifier("membershipPaymentRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
    }

    @Override
    public void send(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Duration delay) {
        long delayMillis = Objects.requireNonNull(delay).toMillis();
        if (delayMillis <= 0L || delayMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Membership payment Rabbit delay is invalid.");
        }
        CorrelationData correlation = new CorrelationData(envelope.messageId());
        try {
            rabbitTemplate.convertAndSend(
                    exchange,
                    routingKey,
                    envelope,
                    message -> {
                        // 持久化和 x-delay 由发布器固定设置，调用方不能临时降低消息可靠性。
                        message.getMessageProperties().setDeliveryMode(
                                MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(envelope.messageId());
                        message.getMessageProperties().setType(envelope.eventType());
                        message.getMessageProperties().setHeader("x-delay", delayMillis);
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw unavailable(null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable(exception);
        } catch (MembershipPaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static MembershipPaymentException unavailable(Throwable cause) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                "Membership payment Rabbit publish was not confirmed.",
                cause);
    }
}
