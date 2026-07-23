package com.example.temperate.service.registration.verification.delivery.rabbit;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.flow.ProtectedLoginCodeAccess;
import com.example.temperate.service.auth.passwordreset.flow.ProtectedPasswordResetAccess;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryLogContext;
import com.example.temperate.service.registration.verification.delivery.retry.VerificationDeliveryRetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 使用 RabbitTemplate 发布验证码投递消息，并同步等待 broker confirm。
 *
 * <p>确认成功才允许请求线程或消费者 ACK 上一阶段状态；本项目不使用 Outbox，因此这里不能声明数据库/Redis 与 RabbitMQ
 * 具备 Exactly Once，只能缩小未确认发布造成的丢失窗口。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.registration.delivery.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitVerificationDeliveryPublisher implements VerificationDeliveryPublisher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RabbitVerificationDeliveryPublisher.class);
    private static final int MESSAGE_ID_LENGTH = 38;

    private final RabbitTemplate rabbitTemplate;
    private final VerificationDeliveryPayloadProtector payloadProtector;
    private final VerificationDeliveryRetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration confirmTimeout;

    @Autowired
    public RabbitVerificationDeliveryPublisher(
            RabbitTemplate rabbitTemplate,
            VerificationDeliveryPayloadProtector payloadProtector,
            MeterRegistry meterRegistry,
            @Value("${app.registration.delivery.rabbit.confirm-timeout:5s}")
                    Duration confirmTimeout) {
        this(
                rabbitTemplate,
                payloadProtector,
                VerificationDeliveryRetryPolicy.defaultPolicy(),
                meterRegistry,
                Clock.systemUTC(),
                confirmTimeout);
    }

    RabbitVerificationDeliveryPublisher(
            RabbitTemplate rabbitTemplate,
            VerificationDeliveryPayloadProtector payloadProtector,
            VerificationDeliveryRetryPolicy retryPolicy,
            MeterRegistry meterRegistry,
            Clock clock,
            Duration confirmTimeout) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
        this.payloadProtector =
                Objects.requireNonNull(payloadProtector, "payloadProtector must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException("confirmTimeout must be positive");
        }
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publishRegistration(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publishRegistration(
                access,
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                operationId,
                request,
                codeExpiresAt);
    }

    @Override
    public void publishRegistration(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publish(messageForRegistration(
                access, channel, deliveryMethod, operationId, request, codeExpiresAt), Duration.ZERO);
    }

    @Override
    public void publishLogin(
            ProtectedLoginCodeAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publishLogin(
                access,
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                operationId,
                request,
                codeExpiresAt);
    }

    @Override
    public void publishLogin(
            ProtectedLoginCodeAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publish(messageForLogin(
                access, channel, deliveryMethod, operationId, request, codeExpiresAt), Duration.ZERO);
    }

    @Override
    public void publishPasswordReset(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publishPasswordReset(
                access,
                channel,
                VerificationDeliveryMethod.defaultFor(channel),
                operationId,
                request,
                codeExpiresAt);
    }

    @Override
    public void publishPasswordReset(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        publish(messageForPasswordReset(
                access, channel, deliveryMethod, operationId, request, codeExpiresAt), Duration.ZERO);
    }

    @Override
    public void publishRetry(VerificationDeliveryMessage current, Duration delay) {
        VerificationDeliveryMessage next = current.nextAttempt(newMessageId(), clock.instant());
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(current);
        LOGGER.debug(
                "event=verification_delivery_retry_created oldMessageId={} newMessageId={} "
                        + "nextAttempt={} delayMs={} traceId={} messageId={} flow={} channel={} "
                        + "deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                current.messageId(),
                next.messageId(),
                next.attemptNo(),
                delay.toMillis(),
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
        publish(next, delay);
    }

    @Override
    public void publishTerminalFailure(
            VerificationDeliveryMessage original,
            String provider,
            String safeReason,
            boolean retryable) {
        VerificationDeliveryTerminalFailureMessage terminal =
                VerificationDeliveryTerminalFailureMessage.from(
                        original, provider, safeReason, retryable, clock.instant());
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(original);
        long startedAtNanos = System.nanoTime();
        LOGGER.debug(
                "event=verification_delivery_terminal_publish_requested exchange={} routingKey={} "
                        + "provider={} safeReason={} retryable={} traceId={} messageId={} flow={} "
                        + "channel={} deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                VerificationDeliveryRabbitNames.TERMINAL_EXCHANGE,
                VerificationDeliveryRabbitNames.TERMINAL_ROUTING_KEY,
                provider,
                safeReason,
                retryable,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
        CorrelationData correlation = new CorrelationData("terminal-" + original.messageId());
        try {
            rabbitTemplate.convertAndSend(
                    VerificationDeliveryRabbitNames.TERMINAL_EXCHANGE,
                    VerificationDeliveryRabbitNames.TERMINAL_ROUTING_KEY,
                    terminal,
                    brokerMessage -> {
                        brokerMessage.getMessageProperties().setMessageId(terminal.messageId());
                        brokerMessage.getMessageProperties().setType(terminal.eventType());
                        brokerMessage.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return brokerMessage;
                    },
                    correlation);
            waitForConfirm(correlation, context, startedAtNanos, false);
        } catch (VerificationDeliveryPublishException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            counter("terminal_confirm_error");
            logPublishFailed(
                    context,
                    "terminal_confirm_error",
                    exception.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos));
            throw new VerificationDeliveryPublishException(
                    "Verification delivery terminal publish failed.", exception);
        }
    }

    private VerificationDeliveryMessage messageForRegistration(
            ProtectedRegistrationAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        return new VerificationDeliveryMessage(
                newMessageId(),
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                clock.instant(),
                newMessageId(),
                VerificationDeliveryFlowKind.REGISTRATION,
                channel,
                deliveryMethod,
                request.purpose(),
                operationId.value(),
                1,
                retryPolicy.maxAttempts(),
                codeExpiresAt,
                access.flowId().value(),
                access.flowCsrfHash().value(),
                access.challengeId().value(),
                access.deviceHash().value(),
                access.globalDeviceHash().value(),
                access.ipHash().value(),
                access.emailCodeId().value(),
                access.phoneCodeId().value(),
                null,
                null,
                payloadProtector.protect(request));
    }

    private VerificationDeliveryMessage messageForLogin(
            ProtectedLoginCodeAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        return new VerificationDeliveryMessage(
                newMessageId(),
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                clock.instant(),
                newMessageId(),
                VerificationDeliveryFlowKind.LOGIN,
                channel,
                deliveryMethod,
                request.purpose(),
                operationId.value(),
                1,
                retryPolicy.maxAttempts(),
                codeExpiresAt,
                access.flowId().value(),
                null,
                access.challengeId().value(),
                access.deviceHash().value(),
                null,
                null,
                null,
                null,
                access.codeId().value(),
                null,
                payloadProtector.protect(request));
    }

    private VerificationDeliveryMessage messageForPasswordReset(
            ProtectedPasswordResetAccess access,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod,
            HmacIdentifier operationId,
            VerificationDeliveryRequest request,
            Instant codeExpiresAt) {
        return new VerificationDeliveryMessage(
                newMessageId(),
                VerificationDeliveryRabbitNames.EVENT_TYPE,
                VerificationDeliveryRabbitNames.SCHEMA_VERSION,
                clock.instant(),
                newMessageId(),
                VerificationDeliveryFlowKind.PASSWORD_RESET,
                channel,
                deliveryMethod,
                request.purpose(),
                operationId.value(),
                1,
                retryPolicy.maxAttempts(),
                codeExpiresAt,
                access.flowId().value(),
                null,
                access.challengeId().value(),
                access.deviceHash().value(),
                access.globalDeviceHash().value(),
                null,
                null,
                null,
                access.codeId().value(),
                access.targetHash().value(),
                payloadProtector.protect(request));
    }

    private void publish(VerificationDeliveryMessage message, Duration delay) {
        VerificationDeliveryLogContext context = VerificationDeliveryLogContext.from(message);
        String routingKey = routingKey(message.channel());
        long delayMs = positiveDelayMillis(delay);
        long startedAtNanos = System.nanoTime();
        LOGGER.debug(
                "event=verification_delivery_publish_requested exchange={} routingKey={} delayMs={} "
                        + "retry={} mandatoryExpected={} traceId={} messageId={} flow={} channel={} "
                        + "deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                VerificationDeliveryRabbitNames.EXCHANGE,
                routingKey,
                delayMs,
                message.attemptNo() > 1,
                delayMs == 0L,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
        CorrelationData correlation = new CorrelationData(message.messageId());
        try {
            rabbitTemplate.convertAndSend(
                    VerificationDeliveryRabbitNames.EXCHANGE,
                    routingKey,
                    message,
                    brokerMessage -> {
                        brokerMessage.getMessageProperties().setMessageId(message.messageId());
                        brokerMessage.getMessageProperties().setType(message.eventType());
                        brokerMessage.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        if (delayMs > 0) {
                            brokerMessage.getMessageProperties().setHeader("x-delay", delayMs);
                        }
                        return brokerMessage;
                    },
                    correlation);
            waitForConfirm(correlation, context, startedAtNanos, delayMs > 0L);
        } catch (VerificationDeliveryPublishException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            counter("confirm_error");
            logPublishFailed(
                    context,
                    "confirm_error",
                    exception.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos));
            throw exception;
        }
    }

    private void waitForConfirm(
            CorrelationData correlation,
            VerificationDeliveryLogContext context,
            long startedAtNanos,
            boolean delayed) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                counter("nacked");
                logPublishFailed(context, "nacked", "none", elapsedMillis(startedAtNanos));
                throw new VerificationDeliveryPublishException("Verification delivery publish was nacked.");
            }
            boolean returnedIgnored = false;
            if (correlation.getReturned() != null) {
                if (!delayed) {
                    counter("returned");
                    logPublishFailed(context, "returned", "none", elapsedMillis(startedAtNanos));
                    throw new VerificationDeliveryPublishException(
                            "Verification delivery publish was returned.");
                }
                // delayed-message 的 Return 不能证明未来路由失败；Confirm ACK 才是本次延迟发布的接受依据。
                returnedIgnored = true;
                counter("returned_ignored_delayed");
                logDelayedReturnIgnored(context, elapsedMillis(startedAtNanos));
            }
            counter("confirmed");
            LOGGER.debug(
                    "event=verification_delivery_publish_confirmed brokerAck=true delayed={} "
                            + "returnedIgnored={} durationMs={} "
                            + "traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                            + "purpose={} attemptNo={} maxAttempts={}",
                    delayed,
                    returnedIgnored,
                    elapsedMillis(startedAtNanos),
                    context.traceId(),
                    context.messageId(),
                    context.flow(),
                    context.channel(),
                    context.deliveryMethod(),
                    context.purpose(),
                    context.attemptNo(),
                    context.maxAttempts());
        } catch (VerificationDeliveryPublishException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            counter("interrupted");
            logPublishFailed(
                    context,
                    "interrupted",
                    exception.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos));
            throw new VerificationDeliveryPublishException(
                    "Verification delivery publish confirm was interrupted.", exception);
        } catch (Exception exception) {
            counter("confirm_error");
            logPublishFailed(
                    context,
                    "confirm_error",
                    exception.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos));
            throw new VerificationDeliveryPublishException(
                    "Verification delivery publish confirm failed.", exception);
        }
    }

    private static void logPublishFailed(
            VerificationDeliveryLogContext context,
            String reason,
            String exceptionClass,
            long durationMs) {
        LOGGER.debug(
                "event=verification_delivery_publish_failed reason={} exceptionClass={} "
                        + "durationMs={} traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                        + "purpose={} attemptNo={} maxAttempts={}",
                reason,
                exceptionClass,
                durationMs,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logDelayedReturnIgnored(
            VerificationDeliveryLogContext context,
            long durationMs) {
        LOGGER.debug(
                "event=verification_delivery_publish_return_ignored "
                        + "reason=returned_ignored_delayed durationMs={} traceId={} messageId={} "
                        + "flow={} channel={} deliveryMethod={} purpose={} attemptNo={} maxAttempts={}",
                durationMs,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static long positiveDelayMillis(Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        return delay.isZero() || delay.isNegative() ? 0L : delay.toMillis();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static String routingKey(VerificationChannel channel) {
        return switch (channel) {
            case EMAIL -> VerificationDeliveryRabbitNames.EMAIL_ROUTING_KEY;
            case SMS -> VerificationDeliveryRabbitNames.SMS_ROUTING_KEY;
        };
    }

    private String newMessageId() {
        return NanoId.randomNanoId(MESSAGE_ID_LENGTH);
    }

    private void counter(String outcome) {
        meterRegistry.counter(
                "ait.auth.verification.delivery.rabbit.publish", "outcome", outcome).increment();
    }
}
