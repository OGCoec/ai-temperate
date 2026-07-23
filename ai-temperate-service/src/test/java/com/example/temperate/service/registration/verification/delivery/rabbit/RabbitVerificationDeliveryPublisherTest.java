package com.example.temperate.service.registration.verification.delivery.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.registration.verification.delivery.retry.VerificationDeliveryRetryPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 验证 RabbitMQ 验证码发布器对延迟重试、Broker Confirm 和 Return 结果输出安全的结构化日志。
 */
class RabbitVerificationDeliveryPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final HmacIdentifier HMAC =
            HmacIdentifier.fromProtectedValue("A".repeat(43));

    @Test
    void initialPublishLogsNonRetryRequestAndConfirmedBrokerAck() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<Message> sentMessage = new AtomicReference<>();
        completePublish(rabbitTemplate, sentMessage, new CorrelationData.Confirm(true, null), false);
        VerificationDeliveryPayloadProtector protector =
                mock(VerificationDeliveryPayloadProtector.class);
        when(protector.protect(any())).thenReturn("protected-payload");
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate, protector);
        ProtectedRegistrationAccess access = new ProtectedRegistrationAccess(
                HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            publisher.publishRegistration(
                    access,
                    VerificationChannel.SMS,
                    HMAC,
                    new VerificationDeliveryRequest("+447911123456", "012345"),
                    NOW.plusSeconds(300));

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_publish_requested")
                    .contains("delayMs=0")
                    .contains("mandatoryExpected=true")
                    .contains("retry=false")
                    .contains("attemptNo=1")
                    .contains("deliveryMethod=sms")
                    .contains("event=verification_delivery_publish_confirmed")
                    .doesNotContain("protected-payload")
                    .doesNotContain("+447911123456")
                    .doesNotContain("012345");
            Object delayHeader = sentMessage.get()
                    .getMessageProperties()
                    .getHeaders()
                    .get("x-delay");
            assertThat(delayHeader).isNull();
        }
    }

    @Test
    void whatsappPublishKeepsDeliveryMethodOnSharedSmsRoute() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<Object> sentPayload = new AtomicReference<>();
        doAnswer(invocation -> {
                    sentPayload.set(invocation.getArgument(2));
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(VerificationDeliveryRabbitNames.EXCHANGE),
                        eq(VerificationDeliveryRabbitNames.SMS_ROUTING_KEY),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        VerificationDeliveryPayloadProtector protector =
                mock(VerificationDeliveryPayloadProtector.class);
        when(protector.protect(any())).thenReturn("protected-payload");
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate, protector);
        ProtectedRegistrationAccess access = new ProtectedRegistrationAccess(
                HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC);

        publisher.publishRegistration(
                access,
                VerificationChannel.SMS,
                VerificationDeliveryMethod.WHATSAPP,
                HMAC,
                new VerificationDeliveryRequest("+447911123456", "012345"),
                NOW.plusSeconds(300));

        assertThat(sentPayload.get())
                .isInstanceOfSatisfying(
                        VerificationDeliveryMessage.class,
                        message -> assertThat(message.deliveryMethod())
                                .isEqualTo(VerificationDeliveryMethod.WHATSAPP));
    }

    @Test
    void delayedRetryLogsCreationRequestAndConfirmedPublishWithoutPayload() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<Message> sentMessage = new AtomicReference<>();
        completePublish(rabbitTemplate, sentMessage, new CorrelationData.Confirm(true, null), false);
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            publisher.publishRetry(message(), Duration.ofSeconds(10));

            String output = logs.joinedMessages();
            assertThat(output)
                    .contains("event=verification_delivery_retry_created")
                    .contains("oldMessageId=message-1")
                    .contains("nextAttempt=2")
                    .contains("delayMs=10000")
                    .contains("event=verification_delivery_publish_requested")
                    .contains("exchange=" + VerificationDeliveryRabbitNames.EXCHANGE)
                    .contains("routingKey=" + VerificationDeliveryRabbitNames.SMS_ROUTING_KEY)
                    .contains("retry=true")
                    .contains("mandatoryExpected=false")
                    .contains("event=verification_delivery_publish_confirmed")
                    .contains("brokerAck=true")
                    .contains("traceId=trace-1")
                    .doesNotContain("protected-payload")
                    .doesNotContain("012345")
                    .doesNotContain("+447911123456");
            assertThat(sentMessage.get()).isNotNull();
            Object delayHeader = sentMessage.get()
                    .getMessageProperties()
                    .getHeaders()
                    .get("x-delay");
            assertThat(delayHeader).isEqualTo(10000L);
        }
    }

    @Test
    void terminalFailureUsesOriginalMessageIdAndPersistentConfirmedPublish() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        AtomicReference<Object> sentPayload = new AtomicReference<>();
        AtomicReference<Message> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
                    sentPayload.set(invocation.getArgument(2));
                    MessagePostProcessor processor = invocation.getArgument(3);
                    CorrelationData correlation = invocation.getArgument(4);
                    sentMessage.set(processor.postProcessMessage(
                            new Message(new byte[0], new MessageProperties())));
                    correlation.getFuture().complete(
                            new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(VerificationDeliveryRabbitNames.TERMINAL_EXCHANGE),
                        eq(VerificationDeliveryRabbitNames.TERMINAL_ROUTING_KEY),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            publisher.publishTerminalFailure(
                    message(), "aliyun_sms", "sms_provider_frequency_limited", false);

            assertThat(sentPayload.get())
                    .isInstanceOfSatisfying(
                            VerificationDeliveryTerminalFailureMessage.class,
                            terminal -> {
                                assertThat(terminal.messageId()).isEqualTo("message-1");
                                assertThat(terminal.protectedPayload())
                                        .isEqualTo("protected-payload");
                                assertThat(terminal.retryable()).isFalse();
                            });
            assertThat(sentMessage.get().getMessageProperties().getMessageId())
                    .isEqualTo("message-1");
            assertThat(sentMessage.get().getMessageProperties().getDeliveryMode())
                    .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            assertThat(sentPayload.get().toString()).doesNotContain("protected-payload");
            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_terminal_publish_requested")
                    .contains("safeReason=sms_provider_frequency_limited")
                    .contains("event=verification_delivery_publish_confirmed")
                    .doesNotContain("protected-payload");
        }
    }

    @Test
    void nackLogsSafeFailureClassification() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        completePublish(
                rabbitTemplate,
                new AtomicReference<>(),
                new CorrelationData.Confirm(false, "provider payload must stay hidden"),
                false);
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            assertThatThrownBy(() -> publisher.publishRetry(message(), Duration.ofSeconds(10)))
                    .isInstanceOf(VerificationDeliveryPublishException.class);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_publish_failed")
                    .contains("reason=nacked")
                    .contains("traceId=trace-1")
                    .doesNotContain("provider payload must stay hidden");
        }
    }

    @Test
    void delayedReturnAfterAckIsIgnoredWithoutImmediateRetryFailure() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        completePublish(
                rabbitTemplate,
                new AtomicReference<>(),
                new CorrelationData.Confirm(true, null),
                true);
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            publisher.publishRetry(message(), Duration.ofSeconds(10));

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_publish_return_ignored")
                    .contains("reason=returned_ignored_delayed")
                    .contains("event=verification_delivery_publish_confirmed")
                    .doesNotContain("event=verification_delivery_publish_failed")
                    .doesNotContain("sensitive broker reply");
        }
    }

    @Test
    void immediateReturnedMessageStillFailsWithoutBrokerReplyText() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        completePublish(
                rabbitTemplate,
                new AtomicReference<>(),
                new CorrelationData.Confirm(true, null),
                true);
        VerificationDeliveryPayloadProtector protector =
                mock(VerificationDeliveryPayloadProtector.class);
        when(protector.protect(any())).thenReturn("protected-payload");
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate, protector);
        ProtectedRegistrationAccess access = new ProtectedRegistrationAccess(
                HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC, HMAC);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            assertThatThrownBy(() -> publisher.publishRegistration(
                            access,
                            VerificationChannel.SMS,
                            HMAC,
                            new VerificationDeliveryRequest("+447911123456", "012345"),
                            NOW.plusSeconds(300)))
                    .isInstanceOf(VerificationDeliveryPublishException.class);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_publish_failed")
                    .contains("reason=returned")
                    .doesNotContain("sensitive broker reply")
                    .doesNotContain("+447911123456")
                    .doesNotContain("012345");
        }
    }

    @Test
    void exceptionalConfirmLogsSafeConfirmErrorWithoutCauseText() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
                    CorrelationData correlation = invocation.getArgument(4);
                    correlation.getFuture().completeExceptionally(
                            new IllegalStateException("raw confirm error contains 012345"));
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(VerificationDeliveryRabbitNames.EXCHANGE),
                        eq(VerificationDeliveryRabbitNames.SMS_ROUTING_KEY),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        RabbitVerificationDeliveryPublisher publisher = publisher(rabbitTemplate);

        try (DebugLogCapture logs = DebugLogCapture.start(
                RabbitVerificationDeliveryPublisher.class)) {
            assertThatThrownBy(() -> publisher.publishRetry(message(), Duration.ofSeconds(10)))
                    .isInstanceOf(VerificationDeliveryPublishException.class);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_publish_failed")
                    .contains("reason=confirm_error")
                    .contains("exceptionClass=ExecutionException")
                    .doesNotContain("raw confirm error")
                    .doesNotContain("012345");
        }
    }

    private static RabbitVerificationDeliveryPublisher publisher(RabbitTemplate rabbitTemplate) {
        return publisher(rabbitTemplate, mock(VerificationDeliveryPayloadProtector.class));
    }

    private static RabbitVerificationDeliveryPublisher publisher(
            RabbitTemplate rabbitTemplate,
            VerificationDeliveryPayloadProtector payloadProtector) {
        return new RabbitVerificationDeliveryPublisher(
                rabbitTemplate,
                payloadProtector,
                VerificationDeliveryRetryPolicy.defaultPolicy(),
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1));
    }

    private static void completePublish(
            RabbitTemplate rabbitTemplate,
            AtomicReference<Message> sentMessage,
            CorrelationData.Confirm confirm,
            boolean returned) {
        doAnswer(invocation -> {
                    MessagePostProcessor processor = invocation.getArgument(3);
                    CorrelationData correlation = invocation.getArgument(4);
                    Message outbound = processor.postProcessMessage(
                            new Message(new byte[0], new MessageProperties()));
                    sentMessage.set(outbound);
                    if (returned) {
                        correlation.setReturned(new ReturnedMessage(
                                outbound,
                                312,
                                "sensitive broker reply",
                                VerificationDeliveryRabbitNames.EXCHANGE,
                                VerificationDeliveryRabbitNames.SMS_ROUTING_KEY));
                    }
                    correlation.getFuture().complete(confirm);
                    return null;
                })
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(VerificationDeliveryRabbitNames.EXCHANGE),
                        eq(VerificationDeliveryRabbitNames.SMS_ROUTING_KEY),
                        any(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));
    }

    private static VerificationDeliveryMessage message() {
        return new VerificationDeliveryMessage(
                "message-1",
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                NOW,
                "trace-1",
                VerificationDeliveryFlowKind.REGISTRATION,
                VerificationChannel.SMS,
                VerificationPurpose.REGISTRATION,
                "operation-hash",
                1,
                6,
                NOW.plusSeconds(300),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "protected-payload");
    }
}
