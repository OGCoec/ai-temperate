package com.example.temperate.service.user.aiconversation.generation.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingConsumer;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationCancelRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationDetachCheck;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRabbitNames;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationMessageRejectedException;
import com.example.temperate.service.user.aiconversation.generation.recovery.AiConversationGenerationRecoveryService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorker;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorkItem;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 使用手动 ACK 消费生成、Owner 控制、失联检查和唯一终态消息，失败有限重试后进入 Quorum DLQ。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationRabbitListeners {

    private static final int BILLING_ATTEMPTS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationGenerationRabbitListeners.class);

    private final AiConversationGenerationWorker worker;
    private final AiConversationGenerationActiveRegistry activeRegistry;
    private final AiConversationGenerationControlService controlService;
    private final AiConversationGenerationCancellationService cancellationService;
    private final AiConversationGenerationBillingConsumer billingConsumer;
    private final AiConversationGenerationRecoveryService recoveryService;
    private final HybridBase64UrlCodec idCodec;
    private final AiConversationMetrics metrics;

    public AiConversationGenerationRabbitListeners(
            AiConversationGenerationWorker worker,
            AiConversationGenerationActiveRegistry activeRegistry,
            AiConversationGenerationControlService controlService,
            AiConversationGenerationCancellationService cancellationService,
            AiConversationGenerationBillingConsumer billingConsumer,
            AiConversationGenerationRecoveryService recoveryService,
            HybridBase64UrlCodec idCodec,
            AiConversationMetrics metrics) {
        this.worker = Objects.requireNonNull(worker);
        this.activeRegistry = Objects.requireNonNull(activeRegistry);
        this.controlService = Objects.requireNonNull(controlService);
        this.cancellationService = Objects.requireNonNull(cancellationService);
        this.billingConsumer = Objects.requireNonNull(billingConsumer);
        this.recoveryService = Objects.requireNonNull(recoveryService);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @RabbitListener(
            queues = AiConversationGenerationRabbitNames.GENERATION_QUEUE,
            containerFactory = "aiConversationGenerationWorkerListenerFactory")
    public void generation(
            AiConversationGenerationEnvelope<AiConversationGenerationRequested> envelope,
            Message message,
            Channel channel) throws IOException {
        try {
            requireEvent(envelope, "AI_GENERATION_REQUESTED");
            worker.execute(envelope.payload().generationPublicId(), envelope.traceId());
            acknowledge(message, channel);
        } catch (RuntimeException failure) {
            logFailure(envelope, "generation", failure);
            reject(message, channel);
        }
    }

    @RabbitListener(
            queues = "#{aiConversationGenerationControlQueue.name}",
            containerFactory = "aiConversationGenerationControlListenerFactory")
    public void control(
            AiConversationGenerationEnvelope<AiConversationGenerationCancelRequested> envelope,
            Message message,
            Channel channel) throws IOException {
        try {
            requireEvent(envelope, "AI_GENERATION_CANCEL_REQUESTED");
            applyControl(envelope.payload().generationPublicId());
            acknowledge(message, channel);
        } catch (RuntimeException failure) {
            logFailure(envelope, "control", failure);
            reject(message, channel);
        }
    }

    @RabbitListener(
            queues = AiConversationGenerationRabbitNames.DETACH_QUEUE,
            containerFactory = "aiConversationGenerationControlListenerFactory")
    public void detachCheck(
            AiConversationGenerationEnvelope<AiConversationGenerationDetachCheck> envelope,
            Message message,
            Channel channel) throws IOException {
        try {
            requireEvent(envelope, "AI_GENERATION_DETACH_CHECK");
            AiConversationGenerationDetachCheck payload = envelope.payload();
            cancellationService.requestDetachedTimeout(
                    idCodec.decode(payload.generationPublicId()),
                    payload.observerEpoch(),
                    payload.detachedAt(),
                    envelope.traceId());
            acknowledge(message, channel);
        } catch (RuntimeException failure) {
            logFailure(envelope, "detach_check", failure);
            reject(message, channel);
        }
    }

    @RabbitListener(
            queues = AiConversationGenerationRabbitNames.TERMINAL_QUEUE,
            containerFactory = "aiConversationGenerationTerminalListenerFactory")
    public void terminal(
            AiConversationGenerationEnvelope<AiConversationGenerationTerminated> envelope,
            Message message,
            Channel channel) throws IOException {
        try {
            requireEvent(envelope, "AI_GENERATION_TERMINATED");
        } catch (RuntimeException invalidMessage) {
            logFailure(envelope, "terminal_contract", invalidMessage);
            reject(message, channel);
            return;
        }
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= BILLING_ATTEMPTS; attempt++) {
            try {
                billingConsumer.consume(envelope.payload(), envelope.traceId());
                acknowledge(message, channel);
                return;
            } catch (AiConversationGenerationMessageRejectedException invalidMessage) {
                logFailure(envelope, "terminal_authority_mismatch", invalidMessage);
                reject(message, channel);
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        try {
            recoveryService.markBillingReconcileRequired(
                    idCodec.decode(envelope.payload().generationPublicId()),
                    "AI_GENERATION_BILLING_RETRIES_EXHAUSTED");
        } catch (RuntimeException reconciliationFailure) {
            if (lastFailure != null) {
                reconciliationFailure.addSuppressed(lastFailure);
            }
        }
        logFailure(envelope, "terminal", lastFailure);
        reject(message, channel);
    }

    private static void requireEvent(
            AiConversationGenerationEnvelope<?> envelope,
            String expectedType) {
        if (envelope.schemaVersion()
                        != AiConversationGenerationEnvelope.CURRENT_SCHEMA_VERSION
                || !expectedType.equals(envelope.eventType())) {
            throw new IllegalArgumentException("Unsupported AI Generation message.");
        }
    }

    private void applyControl(String generationPublicId) {
        AiConversationGenerationWorkItem workItem = controlService.load(
                idCodec.decode(generationPublicId));
        if (workItem != null
                && workItem.generation().getGenerationStatus()
                        == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
            activeRegistry.cancel(generationPublicId);
            return;
        }
        // 终态之后才到达的重复控制消息不能在 Registry 留下永远不会被消费的 pending 标记。
        activeRegistry.clear(generationPublicId);
    }

    private static void acknowledge(Message message, Channel channel) throws IOException {
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private void reject(Message message, Channel channel) throws IOException {
        // false 固定进入 DLQ，禁止异常消息在主队列无限 requeue。
        metrics.rabbitDeadLetter();
        channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
    }

    private static void logFailure(
            AiConversationGenerationEnvelope<?> envelope,
            String phase,
            RuntimeException failure) {
        LOGGER.warn(
                "event=ai_generation_message_dead_letter traceId={} messageId={} "
                        + "phase={} cause={}",
                envelope == null ? "unavailable" : safeLogValue(envelope.traceId()),
                envelope == null ? "unavailable" : envelope.messageId(),
                phase,
                failure == null ? "unavailable" : failure.getClass().getSimpleName());
    }

    private static String safeLogValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "unavailable";
    }
}
