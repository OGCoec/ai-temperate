package com.example.temperate.service.registration.verification.delivery.rabbit;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.flow.LoginCodeFlowStore;
import com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess;
import com.example.temperate.service.auth.passwordreset.flow.PasswordResetFlowStore;
import com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryLogContext;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.retry.VerificationDeliveryRetryPolicy;
import com.example.temperate.service.registration.verification.delivery.status.TwilioWhatsAppStatusStore;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费验证码投递消息，按供应商结果执行成功确认、延迟重试或终态失败审计。
 *
 * <p>手动 ACK 顺序是可靠性边界：成功回写 Redis 后才 ACK；发布下一条延迟消息并释放状态后才 ACK；
 * 终态消息确认后才更新失败状态；若重试或终态消息发布未确认，则当前消息不 ACK，由 broker 重新投递。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.registration.delivery.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class VerificationDeliveryConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerificationDeliveryConsumer.class);

    private final SixDigitVerificationCodeServiceRegistry serviceRegistry;
    private final VerificationProviderResolver providerResolver;
    private final RegistrationFlowStore registrationFlowStore;
    private final LoginCodeFlowStore loginCodeFlowStore;
    private final PasswordResetFlowStore passwordResetFlowStore;
    private final VerificationDeliveryPayloadProtector payloadProtector;
    private final VerificationDeliveryPublisher publisher;
    private final TwilioWhatsAppStatusStore twilioStatusStore;
    private final VerificationDeliveryRetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration providerTimeout;

    @Autowired
    public VerificationDeliveryConsumer(
            SixDigitVerificationCodeServiceRegistry serviceRegistry,
            VerificationProviderResolver providerResolver,
            RegistrationFlowStore registrationFlowStore,
            LoginCodeFlowStore loginCodeFlowStore,
            PasswordResetFlowStore passwordResetFlowStore,
            VerificationDeliveryPayloadProtector payloadProtector,
            VerificationDeliveryPublisher publisher,
            MeterRegistry meterRegistry,
            ObjectProvider<TwilioWhatsAppStatusStore> twilioStatusStoreProvider,
            @Value("${app.registration.delivery.provider-timeout:25s}")
                    Duration providerTimeout) {
        this(
                serviceRegistry,
                providerResolver,
                registrationFlowStore,
                loginCodeFlowStore,
                passwordResetFlowStore,
                payloadProtector,
                publisher,
                twilioStatusStoreProvider.getIfAvailable(),
                VerificationDeliveryRetryPolicy.defaultPolicy(),
                meterRegistry,
                Clock.systemUTC(),
                providerTimeout);
    }

    VerificationDeliveryConsumer(
            SixDigitVerificationCodeServiceRegistry serviceRegistry,
            VerificationProviderResolver providerResolver,
            RegistrationFlowStore registrationFlowStore,
            LoginCodeFlowStore loginCodeFlowStore,
            PasswordResetFlowStore passwordResetFlowStore,
            VerificationDeliveryPayloadProtector payloadProtector,
            VerificationDeliveryPublisher publisher,
            VerificationDeliveryRetryPolicy retryPolicy,
            MeterRegistry meterRegistry,
            Clock clock,
            Duration providerTimeout) {
        this(
                serviceRegistry,
                providerResolver,
                registrationFlowStore,
                loginCodeFlowStore,
                passwordResetFlowStore,
                payloadProtector,
                publisher,
                null,
                retryPolicy,
                meterRegistry,
                clock,
                providerTimeout);
    }

    VerificationDeliveryConsumer(
            SixDigitVerificationCodeServiceRegistry serviceRegistry,
            VerificationProviderResolver providerResolver,
            RegistrationFlowStore registrationFlowStore,
            LoginCodeFlowStore loginCodeFlowStore,
            PasswordResetFlowStore passwordResetFlowStore,
            VerificationDeliveryPayloadProtector payloadProtector,
            VerificationDeliveryPublisher publisher,
            TwilioWhatsAppStatusStore twilioStatusStore,
            VerificationDeliveryRetryPolicy retryPolicy,
            MeterRegistry meterRegistry,
            Clock clock,
            Duration providerTimeout) {
        this.serviceRegistry =
                Objects.requireNonNull(serviceRegistry, "serviceRegistry must not be null");
        this.providerResolver =
                Objects.requireNonNull(providerResolver, "providerResolver must not be null");
        this.registrationFlowStore =
                Objects.requireNonNull(registrationFlowStore, "registrationFlowStore must not be null");
        this.loginCodeFlowStore =
                Objects.requireNonNull(loginCodeFlowStore, "loginCodeFlowStore must not be null");
        this.passwordResetFlowStore =
                Objects.requireNonNull(passwordResetFlowStore, "passwordResetFlowStore must not be null");
        this.payloadProtector =
                Objects.requireNonNull(payloadProtector, "payloadProtector must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.twilioStatusStore = twilioStatusStore;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (providerTimeout == null || providerTimeout.isZero() || providerTimeout.isNegative()) {
            throw new IllegalArgumentException("providerTimeout must be positive");
        }
        this.providerTimeout = providerTimeout;
    }

    @RabbitListener(
            queues = VerificationDeliveryRabbitNames.EMAIL_QUEUE,
            containerFactory = "verificationDeliveryEmailListenerContainerFactory")
    public void consumeEmail(
            VerificationDeliveryMessage deliveryMessage,
            Message brokerMessage,
            Channel channel) throws IOException {
        consume(deliveryMessage, brokerMessage, channel);
    }

    @RabbitListener(
            queues = VerificationDeliveryRabbitNames.SMS_QUEUE,
            containerFactory = "verificationDeliverySmsListenerContainerFactory")
    public void consumeSms(
            VerificationDeliveryMessage deliveryMessage,
            Message brokerMessage,
            Channel channel) throws IOException {
        consume(deliveryMessage, brokerMessage, channel);
    }

    private void consume(
            VerificationDeliveryMessage deliveryMessage,
            Message brokerMessage,
            Channel channel) throws IOException {
        VerificationDeliveryLogContext context =
                VerificationDeliveryLogContext.from(deliveryMessage);
        MessageProperties messageProperties = brokerMessage.getMessageProperties();
        long deliveryTag = brokerMessage.getMessageProperties().getDeliveryTag();
        logMessageReceived(deliveryMessage, messageProperties, context);
        try {
            handle(deliveryMessage);
            channel.basicAck(deliveryTag, false);
            logBrokerAck(context, deliveryTag);
        } catch (VerificationDeliveryPublishException exception) {
            channel.basicNack(deliveryTag, false, true);
            logBrokerNack(
                    context,
                    deliveryTag,
                    "delivery_publish_failed",
                    exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            channel.basicNack(deliveryTag, false, true);
            logBrokerNack(
                    context,
                    deliveryTag,
                    "consumer_failure",
                    exception.getClass().getSimpleName());
        }
    }

    private void handle(VerificationDeliveryMessage message) {
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
        Instant handlingStartedAt = clock.instant();
        if (!handlingStartedAt.isBefore(message.codeExpiresAt())) {
            String unknownReason = findUnknownReason(message);
            if (unknownReason != null) {
                publisher.publishTerminalFailure(
                        message,
                        unknownReason.startsWith("twilio_whatsapp_")
                                ? "twilio_whatsapp" : "unknown",
                        unknownReason,
                        false);
                counter(message, "unresolved", "unknown_audit_republished");
                return;
            }
            publisher.publishTerminalFailure(
                    message, "unresolved", "verification_delivery_expired", false);
            finalizeFailure(message);
            counter(message, "unresolved", "expired");
            logMessageSkipped(context, "expired");
            return;
        }
        if (!claim(message)) {
            String unknownReason = findUnknownReason(message);
            if (unknownReason != null) {
                // 终态审计发布失败后的重投只补发同一审计事件，状态机保证不会再次访问 Twilio。
                publisher.publishTerminalFailure(
                        message,
                        unknownReason.startsWith("twilio_whatsapp_")
                                ? "twilio_whatsapp" : "unknown",
                        unknownReason,
                        false);
                counter(message, "unresolved", "unknown_audit_republished");
                return;
            }
            counter(message, "unresolved", "stale");
            logMessageSkipped(context, "stale");
            return;
        }
        VerificationProvider selectedProvider = null;
        try {
            VerificationDeliveryRequest request = payloadProtector
                    .unprotect(message.protectedPayload())
                    .withValidity(Duration.between(
                            handlingStartedAt, message.codeExpiresAt()));
            // 重试消息拥有新的 messageId，邮件会重新分桶；Broker 原样重投当前消息时仍保持同一供应商。
            selectedProvider = providerResolver.resolveDeliveryAttempt(
                    message.channel(),
                    message.deliveryMethod(),
                    request.destination(),
                    message.messageId());
            SixDigitVerificationCodeService service =
                    serviceRegistry.getRequired(selectedProvider);
            VerificationDeliveryResult result = context
                    .propagate(service.sendCode(request))
                    .block(providerTimeout);
            validateResult(message, result, selectedProvider);
            markAccepted(message, result);
            counter(message, tag(selectedProvider), "accepted");
            logAcceptedOutcome(message, result);
        } catch (RuntimeException failure) {
            String providerTag =
                    selectedProvider == null ? "unresolved" : tag(selectedProvider);
            VerificationDeliveryException deliveryFailure = deliveryFailure(failure, selectedProvider);
            handleFailure(message, providerTag, deliveryFailure);
        }
    }

    private void handleFailure(
            VerificationDeliveryMessage message,
            String providerTag,
            VerificationDeliveryException failure) {
        if (failure.outcome() == VerificationDeliveryOutcome.UNKNOWN) {
            // 先把不可确认状态写入 Redis，再发布审计消息；即使 Rabbit 重投，也会被状态机拦截，不能再次调用 Provider。
            markOutcomeUnknown(message, failure.safeReason());
            publisher.publishTerminalFailure(
                    message, providerTag, failure.safeReason(), false);
            counter(message, providerTag, "unknown");
            logOutcome(message, providerTag, failure);
            return;
        }
        if (shouldRetry(message, failure)) {
            Duration delay = retryPolicy.delayBeforeAttempt(message.attemptNo() + 1)
                    .orElseThrow(() -> new IllegalStateException("Retry delay is missing."));
            publisher.publishRetry(message, delay);
            releaseForRetry(message);
            counter(message, providerTag, "retry_scheduled");
            VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
            LOGGER.debug(
                    "event=verification_delivery_retry_scheduled provider={} nextAttempt={} "
                            + "delayMs={} safeReason={} traceId={} messageId={} flow={} channel={} "
                            + "deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                    providerTag,
                    message.attemptNo() + 1,
                    delay.toMillis(),
                    failure.safeReason(),
                    context.traceId(),
                    context.messageId(),
                    context.flow(),
                    context.channel(),
                    context.deliveryMethod(),
                    context.purpose(),
                    context.attemptNo(),
                    context.maxAttempts());
            return;
        }
        // 终态消息必须先得到 broker Confirm，才能回写 Redis 并 ACK 原消息，避免失败审计静默丢失。
        publisher.publishTerminalFailure(
                message, providerTag, failure.safeReason(), failure.retryable());
        finalizeFailure(message);
        counter(
                message,
                providerTag,
                failure.retryable()
                        ? "final_retryable_terminal"
                        : "final_nonretryable_terminal");
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
        String finalReason = failure.retryable()
                ? message.attemptNo() >= message.maxAttempts()
                        ? "retry_exhausted"
                        : "retry_window_closed"
                : "non_retryable";
        LOGGER.debug(
                "event=verification_delivery_final_failed provider={} reason={} safeReason={} "
                        + "retryable={} traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                        + "purpose={} attemptNo={} maxAttempts={}",
                providerTag,
                finalReason,
                failure.safeReason(),
                failure.retryable(),
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private void logMessageReceived(
            VerificationDeliveryMessage message,
            MessageProperties properties,
            VerificationDeliveryLogContext context) {
        long configuredDelayMs = configuredDelayMillis(properties);
        long queueAgeMs = Math.max(
                0L,
                Duration.between(message.occurredAt(), clock.instant()).toMillis());
        LOGGER.debug(
                "event=verification_delivery_message_received queue={} routingKey={} "
                        + "delayedRetry={} configuredDelayMs={} queueAgeMs={} traceId={} "
                        + "messageId={} flow={} channel={} deliveryMethod={} purpose={} attemptNo={} "
                        + "maxAttempts={}",
                textOrUnavailable(properties.getConsumerQueue()),
                textOrUnavailable(properties.getReceivedRoutingKey()),
                message.attemptNo() > 1 || configuredDelayMs > 0L,
                configuredDelayMs,
                queueAgeMs,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logMessageSkipped(
            VerificationDeliveryLogContext context, String reason) {
        LOGGER.debug(
                "event=verification_delivery_message_skipped reason={} traceId={} messageId={} "
                        + "flow={} channel={} deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                reason,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logBrokerAck(
            VerificationDeliveryLogContext context, long deliveryTag) {
        LOGGER.debug(
                "event=verification_delivery_broker_ack deliveryTag={} traceId={} messageId={} "
                        + "flow={} channel={} deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                deliveryTag,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logBrokerNack(
            VerificationDeliveryLogContext context,
            long deliveryTag,
            String reason,
            String exceptionClass) {
        LOGGER.debug(
                "event=verification_delivery_broker_nack deliveryTag={} requeue=true reason={} "
                        + "exceptionClass={} traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                        + "purpose={} attemptNo={} maxAttempts={}",
                deliveryTag,
                reason,
                exceptionClass,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static long configuredDelayMillis(MessageProperties properties) {
        Long receivedDelay = properties.getReceivedDelayLong();
        if (receivedDelay == null) {
            Object headerDelay = properties.getHeader("x-delay");
            receivedDelay = headerDelay instanceof Number number
                    ? number.longValue()
                    : null;
        }
        if (receivedDelay == null) {
            return 0L;
        }
        return receivedDelay == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(receivedDelay);
    }

    private static String textOrUnavailable(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private boolean shouldRetry(
            VerificationDeliveryMessage message, VerificationDeliveryException failure) {
        if (failure.outcome() != VerificationDeliveryOutcome.EXPLICIT_FAILURE
                || !failure.retryable()
                || message.attemptNo() >= message.maxAttempts()) {
            return false;
        }
        return retryPolicy.delayBeforeAttempt(message.attemptNo() + 1)
                .map(delay -> clock.instant().plus(delay).plus(providerTimeout)
                        .isBefore(message.codeExpiresAt()))
                .orElse(false);
    }

    private boolean claim(VerificationDeliveryMessage message) {
        return switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.claimCodeDeliveryAttempt(
                    registrationAccess(message),
                    message.channel(),
                    hmac(message.operationId()),
                    message.messageId(),
                    message.attemptNo());
            case LOGIN -> loginCodeFlowStore.claimDeliveryAttempt(
                    loginAccess(message),
                    hmac(message.operationId()),
                    message.messageId(),
                    message.attemptNo());
            case PASSWORD_RESET -> passwordResetFlowStore.claimDeliveryAttempt(
                    passwordResetAccess(message),
                    hmac(message.operationId()),
                    message.messageId(),
                    message.attemptNo());
        };
    }

    private void releaseForRetry(VerificationDeliveryMessage message) {
        switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.releaseCodeDeliveryForRetry(
                    registrationAccess(message),
                    message.channel(),
                    hmac(message.operationId()),
                    message.messageId());
            case LOGIN -> loginCodeFlowStore.releaseDeliveryForRetry(
                    loginAccess(message), hmac(message.operationId()), message.messageId());
            case PASSWORD_RESET -> passwordResetFlowStore.releaseDeliveryForRetry(
                    passwordResetAccess(message), hmac(message.operationId()), message.messageId());
        }
    }

    private String findUnknownReason(VerificationDeliveryMessage message) {
        return switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.findCodeDeliveryOutcomeUnknown(
                    registrationAccess(message), message.channel(), hmac(message.operationId()));
            case LOGIN -> loginCodeFlowStore.findDeliveryOutcomeUnknown(
                    loginAccess(message), hmac(message.operationId()));
            case PASSWORD_RESET -> passwordResetFlowStore.findDeliveryOutcomeUnknown(
                    passwordResetAccess(message), hmac(message.operationId()));
        };
    }

    private void markAccepted(
            VerificationDeliveryMessage message, VerificationDeliveryResult result) {
        String providerMessageId = result.providerMessageId();
        if (providerMessageId == null || providerMessageId.isBlank()) {
            // 只有 WhatsApp 工具类会在 Provider 边界强制要求 SID；其他既有渠道可能没有可持久化的外部 ID。
            providerMessageId = "unavailable";
        }
        String providerStatus = result.metadata().providerStatus();
        if (providerStatus == null || providerStatus.isBlank()) {
            providerStatus = "accepted";
        }
        switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.markCodeDeliveryAccepted(
                    registrationAccess(message), message.channel(), hmac(message.operationId()),
                    providerMessageId, providerStatus);
            case LOGIN -> loginCodeFlowStore.markDeliveryAccepted(
                    loginAccess(message), hmac(message.operationId()),
                    providerMessageId, providerStatus);
            case PASSWORD_RESET -> passwordResetFlowStore.markDeliveryAccepted(
                    passwordResetAccess(message), hmac(message.operationId()),
                    providerMessageId, providerStatus);
        }
        if (twilioStatusStore != null && "twilio-whatsapp".equals(result.provider())) {
            Duration remaining = Duration.between(clock.instant(), message.codeExpiresAt());
            try {
                twilioStatusStore.recordAccepted(
                        providerMessageId,
                        message.operationId(),
                        providerStatus,
                        result.acceptedAt(),
                        remaining.plus(Duration.ofMinutes(10)));
            } catch (RuntimeException exception) {
                // SID 索引只服务于回调观测；不能让观测 Redis 故障把已经接受的发送重新判成 UNKNOWN。
                LOGGER.warn("event=twilio_status_index_failed providerMessageIdHash=redacted");
            }
        }
    }

    private void markOutcomeUnknown(VerificationDeliveryMessage message, String safeReason) {
        switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.markCodeDeliveryOutcomeUnknown(
                    registrationAccess(message), message.channel(), hmac(message.operationId()),
                    safeReason);
            case LOGIN -> loginCodeFlowStore.markDeliveryOutcomeUnknown(
                    loginAccess(message), hmac(message.operationId()), safeReason);
            case PASSWORD_RESET -> passwordResetFlowStore.markDeliveryOutcomeUnknown(
                    passwordResetAccess(message), hmac(message.operationId()), safeReason);
        }
    }

    private void finalizeFailure(VerificationDeliveryMessage message) {
        switch (message.flowKind()) {
            case REGISTRATION -> registrationFlowStore.finalizeCodeDeliveryFailure(
                    registrationAccess(message), message.channel(), hmac(message.operationId()));
            case LOGIN -> loginCodeFlowStore.finalizeDeliveryFailure(
                    loginAccess(message), hmac(message.operationId()));
            case PASSWORD_RESET -> passwordResetFlowStore.finalizeDeliveryFailure(
                    passwordResetAccess(message), hmac(message.operationId()));
        }
    }

    private static VerificationDeliveryException deliveryFailure(
            Throwable failure, VerificationProvider selectedProvider) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof VerificationDeliveryException deliveryException) {
                return deliveryException;
            }
            if (current instanceof RegistrationException registrationException
                    && registrationException.code()
                            == RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED) {
                return new VerificationDeliveryException(
                        false,
                        "unresolved",
                        "verification_provider_unsupported",
                        registrationException);
            }
            current = current.getCause();
        }
        if (selectedProvider == VerificationProvider.TWILIO_WHATSAPP) {
            return new VerificationDeliveryException(
                    VerificationDeliveryOutcome.UNKNOWN,
                    false,
                    "twilio-whatsapp",
                    "verification_delivery_outcome_unknown",
                    failure);
        }
        return new VerificationDeliveryException(
                true, "unknown", "verification_delivery_unexpected_failure", failure);
    }

    private static void validateResult(
            VerificationDeliveryMessage message,
            VerificationDeliveryResult result,
            VerificationProvider selectedProvider) {
        if (result == null) {
            if (selectedProvider != VerificationProvider.TWILIO_WHATSAPP) {
                throw new VerificationDeliveryException(
                        true, "unknown", "verification_delivery_empty_result", null);
            }
            throw new VerificationDeliveryException(
                    VerificationDeliveryOutcome.UNKNOWN,
                    false,
                    "unknown",
                    "verification_delivery_empty_result",
                    null);
        }
        if (result.channel() != message.channel()) {
            throw new VerificationDeliveryException(
                    false, result.provider(), "verification_delivery_channel_mismatch", null);
        }
        if (result.deliveryMethod() != message.deliveryMethod()) {
            throw new VerificationDeliveryException(
                    false, result.provider(), "verification_delivery_method_mismatch", null);
        }
    }

    private static ProtectedRegistrationAccess registrationAccess(
            VerificationDeliveryMessage message) {
        return new ProtectedRegistrationAccess(
                hmac(message.flowId()),
                hmac(message.flowCsrfHash()),
                hmac(message.challengeId()),
                hmac(message.deviceHash()),
                hmac(message.globalDeviceHash()),
                hmac(message.ipHash()),
                hmac(message.emailCodeId()),
                hmac(message.phoneCodeId()));
    }

    private static ProtectedLoginCodeAccess loginAccess(VerificationDeliveryMessage message) {
        return new ProtectedLoginCodeAccess(
                hmac(message.flowId()),
                hmac(message.challengeId()),
                hmac(message.deviceHash()),
                hmac(message.codeId()));
    }

    private static ProtectedPasswordResetAccess passwordResetAccess(
            VerificationDeliveryMessage message) {
        return new ProtectedPasswordResetAccess(
                hmac(message.flowId()),
                hmac(message.challengeId()),
                hmac(message.deviceHash()),
                hmac(message.globalDeviceHash()),
                hmac(message.codeId()),
                hmac(message.targetHash()));
    }

    private static HmacIdentifier hmac(String value) {
        return HmacIdentifier.fromProtectedValue(value);
    }

    private void counter(
            VerificationDeliveryMessage message,
            String provider,
            String outcome) {
        String channel = tag(message.channel());
        String deliveryMethod = message.deliveryMethod().name().toLowerCase(Locale.ROOT);
        String flow = message.flowKind().name().toLowerCase(Locale.ROOT);
        meterRegistry.counter(
                "ait.auth.verification.delivery.consume",
                "channel", channel,
                "delivery_method", deliveryMethod,
                "flow", flow,
                "provider", provider,
                "outcome", outcome).increment();
        String providerOutcome = switch (outcome) {
            case "accepted" -> "accepted";
            case "unknown", "unknown_audit_republished" -> "unknown";
            case "retry_scheduled", "final_retryable_terminal", "final_nonretryable_terminal"
                    -> "explicit_failure";
            default -> null;
        };
        if (providerOutcome != null) {
            meterRegistry.counter(
                    "ait.auth.verification.delivery.provider." + providerOutcome,
                    "channel", channel,
                    "delivery_method", deliveryMethod,
                    "flow", flow,
                    "provider", provider).increment();
        }
        if ("unknown".equals(providerOutcome)) {
            meterRegistry.counter(
                    "ait.auth.verification.delivery.retry.suppressed_unknown",
                    "channel", channel,
                    "delivery_method", deliveryMethod,
                    "flow", flow,
                    "provider", provider).increment();
        }
        if ("stale".equals(outcome)) {
            meterRegistry.counter(
                    "ait.auth.verification.delivery.stale_message_skipped",
                    "channel", channel,
                    "delivery_method", deliveryMethod,
                    "flow", flow,
                    "provider", provider).increment();
        }
    }

    private static void logOutcome(
            VerificationDeliveryMessage message,
            String provider,
            VerificationDeliveryException failure) {
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
        LOGGER.debug(
                "event=verification_delivery_outcome_unknown provider={} safeReason={} "
                        + "retryDecision=suppressed_unknown traceId={} messageId={} flow={} "
                        + "channel={} attemptNo={} maxAttempts={}",
                provider,
                failure.safeReason(),
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logAcceptedOutcome(
            VerificationDeliveryMessage message, VerificationDeliveryResult result) {
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
        String safeSid = VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(
                result.providerMessageId());
        String status = result.metadata().providerStatus();
        LOGGER.debug(
                "event=verification_delivery_provider_accepted provider={} outcome=accepted "
                        + "providerMessageId={} providerStatus={} retryDecision=suppressed "
                        + "traceId={} messageId={} flow={} channel={} attemptNo={} maxAttempts={}",
                result.provider(),
                safeSid,
                status == null || status.isBlank() ? "unavailable" : status,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static String tag(VerificationChannel channel) {
        return channel.name().toLowerCase(Locale.ROOT);
    }

    private static String tag(VerificationProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }
}
