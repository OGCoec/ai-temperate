package com.example.temperate.service.user.aiconversation.response.rabbit.impl;

import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseCancelRequested;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseControlPublisher;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseRabbitNames;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 发布持久化直接 SSE Stop 消息并等待 RabbitMQ Confirm，使跨实例取消失败能够显式降级到 Redis 意图和浏览器 Abort。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.direct-response-cancellation",
        name = "rabbit-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitAiConversationDirectResponseControlPublisherImpl
        implements AiConversationDirectResponseControlPublisher {

    private static final String EVENT_TYPE =
            "AI_DIRECT_RESPONSE_CANCEL_REQUESTED";

    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    public RabbitAiConversationDirectResponseControlPublisherImpl(
            @Qualifier("aiConversationDirectResponseRabbitTemplate")
                    RabbitTemplate rabbitTemplate,
            Clock clock) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void publishCancelRequested(
            String requestIdentifier,
            String ownerInstanceId,
            String traceId) {
        AiConversationGenerationEnvelope<AiConversationDirectResponseCancelRequested>
                envelope = AiConversationGenerationEnvelope.of(
                        UUID.randomUUID(),
                        EVENT_TYPE,
                        clock.instant().atOffset(ZoneOffset.UTC),
                        safeTraceId(traceId),
                        new AiConversationDirectResponseCancelRequested(
                                requestIdentifier));
        CorrelationData correlation =
                new CorrelationData(envelope.messageId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    AiConversationDirectResponseRabbitNames.CONTROL_EXCHANGE,
                    AiConversationDirectResponseRabbitNames.controlRoutingKey(
                            ownerInstanceId),
                    envelope,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(
                                MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(
                                correlation.getId());
                        return message;
                    },
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(
                        "AI direct response control message was not accepted.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "AI direct response control confirm was interrupted.",
                    exception);
        } catch (java.util.concurrent.TimeoutException
                | java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException(
                    "AI direct response control confirm failed.", exception);
        }
    }

    private static String safeTraceId(String traceId) {
        return traceId != null
                        && traceId.matches("[A-Za-z0-9_-]{1,128}")
                ? traceId
                : "unavailable";
    }
}
