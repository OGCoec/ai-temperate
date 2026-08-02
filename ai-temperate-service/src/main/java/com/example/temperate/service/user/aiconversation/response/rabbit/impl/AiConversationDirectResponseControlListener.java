package com.example.temperate.service.user.aiconversation.response.rabbit.impl;

import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseActiveRegistry;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseCancelRequested;
import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseRabbitNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在 Owner 实例消费直接 SSE Stop 控制消息，并以手动 ACK 保证取消交付失败时进入死信队列而非无限重入队。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.direct-response-cancellation",
        name = "rabbit-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class AiConversationDirectResponseControlListener {

    private static final String EVENT_TYPE =
            "AI_DIRECT_RESPONSE_CANCEL_REQUESTED";

    private final AiConversationDirectResponseActiveRegistry activeRegistry;

    public AiConversationDirectResponseControlListener(
            AiConversationDirectResponseActiveRegistry activeRegistry) {
        this.activeRegistry = Objects.requireNonNull(activeRegistry);
    }

    @RabbitListener(
            queues = "#{aiConversationDirectResponseControlQueue.name}",
            containerFactory =
                    "aiConversationDirectResponseControlListenerFactory")
    public void cancel(
            AiConversationGenerationEnvelope<
                            AiConversationDirectResponseCancelRequested>
                    envelope,
            Message message,
            Channel channel) throws IOException {
        try {
            if (envelope.schemaVersion()
                            != AiConversationGenerationEnvelope
                                    .CURRENT_SCHEMA_VERSION
                    || !EVENT_TYPE.equals(envelope.eventType())) {
                throw new IllegalArgumentException(
                        "Unsupported AI direct response control message.");
            }
            activeRegistry.cancel(
                    envelope.payload().requestIdentifier(),
                    AiConversationInterruptionSource.USER_STOP);
            channel.basicAck(
                    message.getMessageProperties().getDeliveryTag(), false);
        } catch (RuntimeException failure) {
            // 无效或执行失败的控制消息固定进入 DLQ，禁止控制队列无限重放。
            channel.basicReject(
                    message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
