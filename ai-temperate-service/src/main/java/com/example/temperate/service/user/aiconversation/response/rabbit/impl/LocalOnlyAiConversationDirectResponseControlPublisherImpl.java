package com.example.temperate.service.user.aiconversation.response.rabbit.impl;

import com.example.temperate.service.user.aiconversation.response.rabbit.AiConversationDirectResponseControlPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在测试或明确关闭 RabbitMQ 控制拓扑时提供本机降级边界；跨实例请求由 Redis Stop 意图和浏览器 Abort 有限收敛。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.direct-response-cancellation",
        name = "rabbit-enabled",
        havingValue = "false")
public final class LocalOnlyAiConversationDirectResponseControlPublisherImpl
        implements AiConversationDirectResponseControlPublisher {

    @Override
    public void publishCancelRequested(
            String requestIdentifier,
            String ownerInstanceId,
            String traceId) {
        throw new IllegalStateException(
                "AI direct response RabbitMQ control is disabled.");
    }
}
