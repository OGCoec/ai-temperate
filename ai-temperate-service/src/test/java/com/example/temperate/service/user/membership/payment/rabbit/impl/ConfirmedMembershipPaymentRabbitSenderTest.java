package com.example.temperate.service.user.membership.payment.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 该单元测试是来约束会员支付消息强制持久化、携带 x-delay，并且只有 Publisher Confirm ACK 才返回成功。
 */
final class ConfirmedMembershipPaymentRabbitSenderTest {

    @Test
    void ackedMessageIsPersistentAndCarriesDelay() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AtomicReference<Message> sent = new AtomicReference<>();
        doAnswer(invocation -> {
            MessagePostProcessor processor = invocation.getArgument(3);
            CorrelationData correlation = invocation.getArgument(4);
            sent.set(processor.postProcessMessage(
                    new Message(new byte[0], new MessageProperties())));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        new ConfirmedMembershipPaymentRabbitSender(template).send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope(),
                Duration.ofSeconds(10));

        assertThat(sent.get().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        Object delayHeader = sent.get().getMessageProperties().getHeader("x-delay");
        assertThat(delayHeader).isEqualTo(10_000L);
    }

    @Test
    void brokerNackProducesControlledRabbitUnavailableError() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(
                    new CorrelationData.Confirm(false, "test-nack"));
            return null;
        }).when(template).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        assertThatThrownBy(() -> new ConfirmedMembershipPaymentRabbitSender(template).send(
                MembershipPaymentRabbitNames.PAYMENT_EXCHANGE,
                MembershipPaymentRabbitNames.PAYMENT_ROUTING_KEY,
                envelope(),
                Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE));
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope() {
        return new MembershipPaymentRabbitEnvelope<>(
                id((byte) 3),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                1,
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC),
                "trace-test",
                new MembershipPaymentCheckMessage(id((byte) 2), 0));
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }
}
