package com.example.temperate.service.user.aiconversation.generation.cancellation;

import java.time.OffsetDateTime;

/**
 * 定义用户 Stop、管理员取消和观察者失联超时的可信 PostgreSQL CAS 取消边界。
 */
public interface AiConversationGenerationCancellationService {

    AiConversationGenerationCancellationResult requestUserStop(
            long userId,
            byte[] generationId);

    AiConversationGenerationCancellationResult requestAdminCancel(
            byte[] generationId);

    AiConversationGenerationCancellationResult requestDetachedTimeout(
            byte[] generationId,
            long observerEpoch,
            OffsetDateTime detachedAt,
            String traceId);
}
