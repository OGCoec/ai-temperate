package com.example.temperate.service.registration.verification.delivery.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowStore;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryLogContext;
import com.example.temperate.service.registration.verification.delivery.retry.VerificationDeliveryRetryPolicy;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/**
 * 验证验证码投递消费者在手动 ACK 前后保持 Redis 状态和 RabbitMQ 重试发布的顺序。
 */
class VerificationDeliveryConsumerTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");
    private static final String HMAC = "A".repeat(43);

    private RegistrationFlowStore registrationFlowStore;
    private VerificationDeliveryPublisher publisher;
    private SixDigitVerificationCodeServiceRegistry registry;
    private SixDigitVerificationCodeService gmailService;
    private VerificationProviderResolver providerResolver;
    private VerificationDeliveryConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        registry = mock(SixDigitVerificationCodeServiceRegistry.class);
        registrationFlowStore = mock(RegistrationFlowStore.class);
        publisher = mock(VerificationDeliveryPublisher.class);
        gmailService = mock(SixDigitVerificationCodeService.class);
        providerResolver = mock(VerificationProviderResolver.class);
        channel = mock(Channel.class);
        when(providerResolver.resolveDeliveryAttempt(
                        VerificationChannel.EMAIL,
                        VerificationDeliveryMethod.EMAIL,
                        "alice@example.test",
                        "message-1"))
                .thenReturn(VerificationProvider.GMAIL);
        when(registry.getRequired(VerificationProvider.GMAIL)).thenReturn(gmailService);
        consumer = new VerificationDeliveryConsumer(
                registry,
                providerResolver,
                registrationFlowStore,
                mock(LoginCodeFlowStore.class),
                mock(PasswordResetFlowStore.class),
                new VerificationDeliveryPayloadProtector(new byte[32], new ObjectMapper()),
                publisher,
                VerificationDeliveryRetryPolicy.defaultPolicy(),
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(25));
    }

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() {
        boolean hasAutowiredProductionConstructor = Arrays.stream(
                        VerificationDeliveryConsumer.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .anyMatch(constructor -> constructor.isAnnotationPresent(Autowired.class));

        assertTrue(
                hasAutowiredProductionConstructor,
                "公开生产构造器必须显式标注 @Autowired，避免测试构造器导致 Spring 回退查找无参构造器");
    }

    @Test
    void successMarksRedisThenAcknowledgesMessage() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.EMAIL, "gmail", "gmail-message-id", NOW)));

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(registrationFlowStore).markCodeDeliveryAccepted(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.EMAIL),
                eq(HmacIdentifier.fromProtectedValue(HMAC)),
                eq("gmail-message-id"),
                eq("accepted"));
        verify(providerResolver).resolveDeliveryAttempt(
                VerificationChannel.EMAIL,
                VerificationDeliveryMethod.EMAIL,
                "alice@example.test",
                "message-1");
        ArgumentCaptor<VerificationDeliveryRequest> requestCaptor =
                ArgumentCaptor.forClass(VerificationDeliveryRequest.class);
        verify(gmailService).sendCode(requestCaptor.capture());
        assertThat(requestCaptor.getValue().validity()).isEqualTo(Duration.ofMinutes(5));
        verify(channel).basicAck(99L, false);
    }

    @Test
    void successLogsRabbitLifecycleAndPropagatesContextWithoutProviderEvents()
            throws IOException {
        VerificationDeliveryMessage message = message(1);
        AtomicReference<VerificationDeliveryLogContext> propagatedContext =
                new AtomicReference<>();
        ContextCapturingService service = new ContextCapturingService(propagatedContext);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(registry.getRequired(VerificationProvider.GMAIL)).thenReturn(service);
        Message brokerMessage = brokerMessage();
        brokerMessage.getMessageProperties()
                .setConsumerQueue(VerificationDeliveryRabbitNames.EMAIL_QUEUE);
        brokerMessage.getMessageProperties()
                .setReceivedRoutingKey(VerificationDeliveryRabbitNames.EMAIL_ROUTING_KEY);
        brokerMessage.getMessageProperties().setReceivedDelayLong(10000L);

        try (DebugLogCapture logs = DebugLogCapture.start(VerificationDeliveryConsumer.class)) {
            consumer.consumeEmail(message, brokerMessage, channel);

            String output = logs.joinedMessages();
            assertThat(output)
                    .contains("event=verification_delivery_message_received")
                    .contains("queue=" + VerificationDeliveryRabbitNames.EMAIL_QUEUE)
                    .contains("routingKey=" + VerificationDeliveryRabbitNames.EMAIL_ROUTING_KEY)
                    .contains("delayedRetry=true")
                    .contains("configuredDelayMs=10000")
                    .contains("event=verification_delivery_broker_ack")
                    .contains("traceId=trace-1")
                    .contains("messageId=message-1")
                    .contains("deliveryMethod=email")
                    .doesNotContain("event=verification_delivery_provider_selected")
                    .doesNotContain("event=verification_delivery_provider_response")
                    .doesNotContain("event=verification_delivery_provider_completed")
                    .doesNotContain("alice@example.test")
                    .doesNotContain("012345")
                    .doesNotContain("protectedPayload");
        }

        assertThat(propagatedContext.get()).isNotNull();
        assertThat(propagatedContext.get().traceId()).isEqualTo("trace-1");
        assertThat(propagatedContext.get().messageId()).isEqualTo("message-1");
        assertThat(propagatedContext.get().deliveryMethod()).isEqualTo("email");
        assertThat(propagatedContext.get().attemptNo()).isEqualTo(1);
    }

    @Test
    void retryableFailurePublishesDelayedRetryBeforeAcknowledging() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(publisher).publishRetry(message, Duration.ofSeconds(10));
        verify(registrationFlowStore).releaseCodeDeliveryForRetry(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.EMAIL),
                eq(HmacIdentifier.fromProtectedValue(HMAC)),
                eq("message-1"));
        verify(channel).basicAck(99L, false);
    }

    @Test
    void unknownProviderOutcomeIsRecordedWithoutSchedulingRetry() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        VerificationDeliveryOutcome.UNKNOWN,
                        false,
                        "gmail",
                        "verification_delivery_outcome_unknown",
                        null)));

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(registrationFlowStore).markCodeDeliveryOutcomeUnknown(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.EMAIL),
                eq(HmacIdentifier.fromProtectedValue(HMAC)),
                eq("verification_delivery_outcome_unknown"));
        verify(publisher).publishTerminalFailure(
                message, "gmail", "verification_delivery_outcome_unknown", false);
        verify(publisher, never()).publishRetry(any(), any());
        verify(registrationFlowStore, never()).releaseCodeDeliveryForRetry(
                any(), any(), any(), any());
        verify(registrationFlowStore, never()).finalizeCodeDeliveryFailure(
                any(), any(), any());
        verify(channel).basicAck(99L, false);
    }

    @Test
    void redeliveredUnknownMessageOnlyRepublishesAuditAndNeverCallsProvider()
            throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(false);
        when(registrationFlowStore.findCodeDeliveryOutcomeUnknown(
                any(), eq(VerificationChannel.EMAIL), any()))
                .thenReturn("verification_delivery_outcome_unknown");

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(publisher).publishTerminalFailure(
                message, "unknown", "verification_delivery_outcome_unknown", false);
        verify(gmailService, never()).sendCode(any());
        verify(channel).basicAck(99L, false);
    }

    @Test
    void retryLogsScheduleAndAckWithoutDuplicatingAopProviderEvents() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));

        try (DebugLogCapture logs = DebugLogCapture.start(VerificationDeliveryConsumer.class)) {
            consumer.consumeEmail(message, brokerMessage(), channel);

            String output = logs.joinedMessages();
            assertThat(output)
                    .contains("safeReason=gmail_timeout")
                    .contains("event=verification_delivery_retry_scheduled")
                    .contains("nextAttempt=2")
                    .contains("delayMs=10000")
                    .contains("event=verification_delivery_broker_ack")
                    .doesNotContain("event=verification_delivery_provider_selected")
                    .doesNotContain("event=verification_delivery_provider_response")
                    .doesNotContain("event=verification_delivery_provider_completed");
        }
    }

    @Test
    void sixthFailurePublishesTerminalThenFinalizesAndAcknowledges() throws IOException {
        VerificationDeliveryMessage message = message(6);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(6)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));

        consumer.consumeEmail(message, brokerMessage(), channel);

        InOrder order = inOrder(publisher, registrationFlowStore, channel);
        order.verify(publisher).publishTerminalFailure(
                message, "gmail", "gmail_timeout", true);
        order.verify(registrationFlowStore).finalizeCodeDeliveryFailure(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.EMAIL),
                eq(HmacIdentifier.fromProtectedValue(HMAC)));
        verify(publisher, never()).publishRetry(any(), any());
        order.verify(channel).basicAck(99L, false);
    }

    @Test
    void nonRetryableFailurePublishesTerminalWithoutSchedulingDelay() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        false, "gmail", "gmail_request_rejected", null)));

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(publisher).publishTerminalFailure(
                message, "gmail", "gmail_request_rejected", false);
        verify(publisher, never()).publishRetry(any(), any());
        verify(registrationFlowStore).finalizeCodeDeliveryFailure(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.EMAIL),
                eq(HmacIdentifier.fromProtectedValue(HMAC)));
        verify(channel).basicAck(99L, false);
    }

    @Test
    void exhaustedRetryLogsFinalFailure() throws IOException {
        VerificationDeliveryMessage message = message(6);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(6)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));

        try (DebugLogCapture logs = DebugLogCapture.start(VerificationDeliveryConsumer.class)) {
            consumer.consumeEmail(message, brokerMessage(), channel);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_final_failed")
                    .contains("reason=retry_exhausted")
                    .contains("safeReason=gmail_timeout")
                    .contains("attemptNo=6")
                    .contains("maxAttempts=6");
        }
    }

    @Test
    void retryPublishFailureNacksCurrentMessageForRedelivery() throws IOException {
        VerificationDeliveryMessage message = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));
        doThrow(new VerificationDeliveryPublishException("confirm failed"))
                .when(publisher)
                .publishRetry(message, Duration.ofSeconds(10));

        try (DebugLogCapture logs = DebugLogCapture.start(VerificationDeliveryConsumer.class)) {
            consumer.consumeEmail(message, brokerMessage(), channel);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_broker_nack")
                    .contains("requeue=true")
                    .contains("reason=delivery_publish_failed")
                    .doesNotContain("confirm failed");
        }

        verify(registrationFlowStore, never()).releaseCodeDeliveryForRetry(
                any(), any(), any(), any());
        verify(channel).basicNack(99L, false, true);
    }

    @Test
    void terminalPublishFailureNacksWithoutFinalizingFlow() throws IOException {
        VerificationDeliveryMessage message = message(6);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(6)))
                .thenReturn(true);
        when(gmailService.sendCode(any())).thenReturn(Mono.error(
                new VerificationDeliveryException(
                        true, "gmail", "gmail_timeout", null)));
        doThrow(new VerificationDeliveryPublishException("terminal confirm failed"))
                .when(publisher)
                .publishTerminalFailure(message, "gmail", "gmail_timeout", true);

        consumer.consumeEmail(message, brokerMessage(), channel);

        verify(registrationFlowStore, never()).finalizeCodeDeliveryFailure(
                any(), any(), any());
        verify(channel).basicNack(99L, false, true);
    }

    @Test
    void expiredAndStaleMessagesLogSafeSkipReasons() throws IOException {
        VerificationDeliveryMessage expired = message(
                1,
                VerificationChannel.EMAIL,
                "alice@example.test",
                NOW.minusSeconds(30),
                NOW.minusSeconds(1));
        VerificationDeliveryMessage stale = message(1);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.EMAIL), any(), eq("message-1"), eq(1)))
                .thenReturn(false);

        try (DebugLogCapture logs = DebugLogCapture.start(VerificationDeliveryConsumer.class)) {
            consumer.consumeEmail(expired, brokerMessage(), channel);
            consumer.consumeEmail(stale, brokerMessage(), channel);

            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_message_skipped reason=expired")
                    .contains("event=verification_delivery_message_skipped reason=stale")
                    .doesNotContain("alice@example.test")
                    .doesNotContain("012345");
        }
        verify(publisher).publishTerminalFailure(
                expired, "unresolved", "verification_delivery_expired", false);
    }

    @Test
    void internationalSmsUsesProviderResolvedFromProtectedDestination() throws IOException {
        VerificationDeliveryMessage message =
                message(1, VerificationChannel.SMS, "+447911123456");
        SixDigitVerificationCodeService twilioService =
                mock(SixDigitVerificationCodeService.class);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.SMS), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(providerResolver.resolveDeliveryAttempt(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.SMS,
                        "+447911123456",
                        "message-1"))
                .thenReturn(VerificationProvider.TWILIO_SMS);
        when(registry.getRequired(VerificationProvider.TWILIO_SMS))
                .thenReturn(twilioService);
        when(twilioService.sendCode(any())).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.SMS,
                        "twilio-verify",
                        "VE00000000000000000000000000000000",
                        NOW)));

        consumer.consumeSms(message, brokerMessage(), channel);

        verify(twilioService).sendCode(any());
        verify(registrationFlowStore).markCodeDeliveryAccepted(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.SMS),
                eq(HmacIdentifier.fromProtectedValue(HMAC)),
                eq("VE00000000000000000000000000000000"),
                eq("accepted"));
        verify(channel).basicAck(99L, false);
    }

    @Test
    void internationalWhatsappUsesFifthProviderOnSharedSmsConsumer() throws IOException {
        VerificationDeliveryMessage message = message(
                1,
                VerificationChannel.SMS,
                VerificationDeliveryMethod.WHATSAPP,
                "+447911123456");
        SixDigitVerificationCodeService whatsappService =
                mock(SixDigitVerificationCodeService.class);
        when(registrationFlowStore.claimCodeDeliveryAttempt(
                any(), eq(VerificationChannel.SMS), any(), eq("message-1"), eq(1)))
                .thenReturn(true);
        when(providerResolver.resolveDeliveryAttempt(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "+447911123456",
                        "message-1"))
                .thenReturn(VerificationProvider.TWILIO_WHATSAPP);
        when(registry.getRequired(VerificationProvider.TWILIO_WHATSAPP))
                .thenReturn(whatsappService);
        when(whatsappService.sendCode(any())).thenReturn(Mono.just(
                new VerificationDeliveryResult(
                        VerificationChannel.SMS,
                        VerificationDeliveryMethod.WHATSAPP,
                        "twilio_whatsapp",
                        "SM00000000000000000000000000000000",
                        NOW)));

        consumer.consumeSms(message, brokerMessage(), channel);

        verify(whatsappService).sendCode(any());
        verify(registrationFlowStore).markCodeDeliveryAccepted(
                any(ProtectedRegistrationAccess.class),
                eq(VerificationChannel.SMS),
                eq(HmacIdentifier.fromProtectedValue(HMAC)),
                eq("SM00000000000000000000000000000000"),
                eq("accepted"));
        verify(publisher, never()).publishRetry(any(), any());
        verify(channel).basicAck(99L, false);
    }

    private VerificationDeliveryMessage message(int attemptNo) {
        return message(attemptNo, VerificationChannel.EMAIL, "alice@example.test");
    }

    private VerificationDeliveryMessage message(
            int attemptNo,
            VerificationChannel verificationChannel,
            String destination) {
        return message(
                attemptNo,
                verificationChannel,
                VerificationDeliveryMethod.defaultFor(verificationChannel),
                destination);
    }

    private VerificationDeliveryMessage message(
            int attemptNo,
            VerificationChannel verificationChannel,
            VerificationDeliveryMethod deliveryMethod,
            String destination) {
        return message(
                attemptNo,
                verificationChannel,
                deliveryMethod,
                destination,
                NOW,
                NOW.plusSeconds(300));
    }

    private VerificationDeliveryMessage message(
            int attemptNo,
            VerificationChannel verificationChannel,
            String destination,
            Instant occurredAt,
            Instant codeExpiresAt) {
        return message(
                attemptNo,
                verificationChannel,
                VerificationDeliveryMethod.defaultFor(verificationChannel),
                destination,
                occurredAt,
                codeExpiresAt);
    }

    private VerificationDeliveryMessage message(
            int attemptNo,
            VerificationChannel verificationChannel,
            VerificationDeliveryMethod deliveryMethod,
            String destination,
            Instant occurredAt,
            Instant codeExpiresAt) {
        VerificationDeliveryRequest request = new VerificationDeliveryRequest(
                destination, "012345", VerificationPurpose.REGISTRATION);
        VerificationDeliveryPayloadProtector protector =
                new VerificationDeliveryPayloadProtector(new byte[32], new ObjectMapper());
        return new VerificationDeliveryMessage(
                "message-1",
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                occurredAt,
                "trace-1",
                VerificationDeliveryFlowKind.REGISTRATION,
                verificationChannel,
                deliveryMethod,
                VerificationPurpose.REGISTRATION,
                HMAC,
                attemptNo,
                6,
                codeExpiresAt,
                HMAC,
                HMAC,
                HMAC,
                HMAC,
                HMAC,
                HMAC,
                HMAC,
                HMAC,
                null,
                null,
                protector.protect(request));
    }

    private static Message brokerMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(99L);
        return new Message(new byte[0], properties);
    }

    /**
     * 在测试中读取 Reactor Context，证明供应商 Mono 跨异步边界仍可获得同一投递关联信息。
     */
    private static final class ContextCapturingService
            implements SixDigitVerificationCodeService {

        private final AtomicReference<VerificationDeliveryLogContext> capturedContext;

        private ContextCapturingService(
                AtomicReference<VerificationDeliveryLogContext> capturedContext) {
            this.capturedContext = capturedContext;
        }

        @Override
        public VerificationProvider type() {
            return VerificationProvider.GMAIL;
        }

        @Override
        public Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request) {
            return Mono.deferContextual(contextView -> {
                capturedContext.set(VerificationDeliveryLogContext.current(contextView));
                return Mono.just(new VerificationDeliveryResult(
                        VerificationChannel.EMAIL,
                        "gmail",
                        "gmail-message-id",
                        NOW));
            });
        }

        @Override
        public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
            throw new UnsupportedOperationException("This test only covers delivery.");
        }
    }
}
