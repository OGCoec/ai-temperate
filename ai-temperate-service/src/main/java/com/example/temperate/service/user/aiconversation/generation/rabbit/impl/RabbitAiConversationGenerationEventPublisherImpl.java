package com.example.temperate.service.user.aiconversation.generation.rabbit.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationCancelRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationDetachCheck;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRabbitNames;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 使用现有唯一 RabbitMQ 连接发布持久消息并同步等待 Confirm，延迟消息只使用 x-delay 承载宽限期。
 *
 * <p>PostgreSQL 与 RabbitMQ 不具备原子性；发布失败由调用方冻结 SYSTEM_FAILED 或交给分钟级恢复任务，
 * 本实现不宣称 Exactly Once。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class RabbitAiConversationGenerationEventPublisherImpl
        implements AiConversationGenerationEventPublisher {

    private static final String REQUESTED = "AI_GENERATION_REQUESTED";
    private static final String CANCEL_REQUESTED = "AI_GENERATION_CANCEL_REQUESTED";
    private static final String DETACH_CHECK = "AI_GENERATION_DETACH_CHECK";
    private static final String TERMINATED = "AI_GENERATION_TERMINATED";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            RabbitAiConversationGenerationEventPublisherImpl.class);

    private final RabbitTemplate rabbitTemplate;
    private final AiConversationAsyncGenerationProperties properties;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public RabbitAiConversationGenerationEventPublisherImpl(
            @Qualifier("aiConversationGenerationRabbitTemplate") RabbitTemplate rabbitTemplate,
            AiConversationAsyncGenerationProperties properties,
            AiConversationMetrics metrics,
            Clock clock) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void publishGenerationRequested(
            String generationPublicId,
            String usagePublicId,
            String traceId) {
        publish(
                AiConversationGenerationRabbitNames.GENERATION_EXCHANGE,
                AiConversationGenerationRabbitNames.workerRoutingKeyV2(
                        properties.instanceId()),
                envelope(
                        REQUESTED,
                        traceId,
                        new AiConversationGenerationRequested(
                                generationPublicId, usagePublicId)),
                null);
        metrics.generationQueued();
    }

    @Override
    public void publishCancelRequested(
            String generationPublicId,
            String cancelSource,
            int cancelVersion,
            String ownerInstanceId,
            String traceId) {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            throw new IllegalArgumentException(
                    "AI generation cancellation requires an owner instance.");
        }
        publish(
                AiConversationGenerationRabbitNames.CONTROL_EXCHANGE,
                AiConversationGenerationRabbitNames.controlRoutingKey(ownerInstanceId),
                envelope(
                        CANCEL_REQUESTED,
                        traceId,
                new AiConversationGenerationCancelRequested(
                                generationPublicId,
                                cancelSource,
                                cancelVersion)),
                null);
    }

    @Override
    public void publishDetachCheck(
            String generationPublicId,
            long observerEpoch,
            OffsetDateTime detachedAt,
            String traceId) {
        long delay = properties.detachGrace().toMillis();
        publish(
                AiConversationGenerationRabbitNames.DETACH_EXCHANGE,
                AiConversationGenerationRabbitNames.DETACH_ROUTING_KEY,
                envelope(
                        DETACH_CHECK,
                        traceId,
                        new AiConversationGenerationDetachCheck(
                                generationPublicId,
                                observerEpoch,
                                detachedAt)),
                delay);
    }

    @Override
    public void publishTerminated(
            String generationPublicId,
            String usagePublicId,
            String terminalType,
            String terminalReason,
            int terminalVersion,
            String traceId) {
        publish(
                AiConversationGenerationRabbitNames.TERMINAL_EXCHANGE,
                AiConversationGenerationRabbitNames.TERMINAL_ROUTING_KEY_V2,
                envelope(
                        TERMINATED,
                        traceId,
                        new AiConversationGenerationTerminated(
                                generationPublicId,
                                usagePublicId,
                                terminalType,
                                terminalReason,
                                terminalVersion)),
                null);
        metrics.terminalPublished();
    }

    private <T> AiConversationGenerationEnvelope<T> envelope(
            String eventType,
            String traceId,
            T payload) {
        return AiConversationGenerationEnvelope.of(
                UUID.randomUUID(),
                eventType,
                clock.instant().atOffset(ZoneOffset.UTC),
                safeTraceId(traceId),
                payload);
    }

    private void publish(
            String exchange,
            String routingKey,
            AiConversationGenerationEnvelope<?> payload,
            Long delayMillis) {
        CorrelationData correlation = new CorrelationData(payload.messageId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    exchange,
                    routingKey,
                    payload,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(
                                MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(correlation.getId());
                        if (delayMillis != null) {
                            message.getMessageProperties().setHeader("x-delay", delayMillis);
                        }
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("AI generation message was negatively acknowledged.");
            }
            if (delayMillis == null && correlation.getReturned() != null) {
                throw new IllegalStateException("AI generation message was returned as unroutable.");
            }
        } catch (InterruptedException exception) {
            metrics.rabbitConfirmFailure();
            logPublishFailure(payload, exchange, exception);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI generation message confirm was interrupted.", exception);
        } catch (java.util.concurrent.TimeoutException
                | java.util.concurrent.ExecutionException exception) {
            metrics.rabbitConfirmFailure();
            logPublishFailure(payload, exchange, exception);
            throw new IllegalStateException("AI generation message confirm failed.", exception);
        } catch (RuntimeException exception) {
            metrics.rabbitConfirmFailure();
            logPublishFailure(payload, exchange, exception);
            throw exception;
        }
    }

    private static String safeTraceId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            return "unavailable";
        }
        return value;
    }

    private static void logPublishFailure(
            AiConversationGenerationEnvelope<?> envelope,
            String exchange,
            Exception failure) {
        LOGGER.warn(
                "event=ai_generation_message_publish_failed traceId={} messageId={} "
                        + "exchange={} cause={}",
                safeTraceId(envelope.traceId()),
                envelope.messageId(),
                exchange,
                failure.getClass().getSimpleName());
    }
}
