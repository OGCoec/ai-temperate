package com.example.temperate.service.user.aiconversation.generation.observer;

/**
 * 定义 SSE 观察者附着、快照恢复和按预期 epoch 失联标记的边界，不负责取消模型或退款。
 */
public interface AiConversationGenerationObserverService {

    AiConversationGenerationObserverSession observe(
            long userId,
            byte[] generationId);
}
