package com.example.temperate.service.user.aiconversation.generation.rabbit;

import java.time.OffsetDateTime;

/**
 * 定义生成命令、Owner 取消、失联检查和唯一终态的可靠 RabbitMQ 发布边界。
 */
public interface AiConversationGenerationEventPublisher {

    void publishGenerationRequested(
            String generationPublicId,
            String usagePublicId,
            String traceId);

    void publishCancelRequested(
            String generationPublicId,
            String cancelSource,
            int cancelVersion,
            String ownerInstanceId,
            String traceId);

    void publishDetachCheck(
            String generationPublicId,
            long observerEpoch,
            OffsetDateTime detachedAt,
            String traceId);

    void publishTerminated(
            String generationPublicId,
            String usagePublicId,
            String terminalType,
            String terminalReason,
            int terminalVersion,
            String traceId);
}
