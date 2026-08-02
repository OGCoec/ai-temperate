package com.example.temperate.service.user.aiconversation.generation.observer;

/**
 * 定义 SSE 结束时按预期 epoch 写入 DETACHED 并提交延迟检查事件的独立事务代理边界。
 */
public interface AiConversationGenerationObserverStateService {

    void detach(
            long userId,
            byte[] generationId,
            String generationPublicId,
            long expectedEpoch,
            String traceId);
}
