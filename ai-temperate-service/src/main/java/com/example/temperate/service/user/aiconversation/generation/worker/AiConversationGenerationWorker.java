package com.example.temperate.service.user.aiconversation.generation.worker;

/**
 * 定义消费 Generation 请求后在 Rabbit 线程中独立调用上游并冻结唯一终态的执行边界。
 */
public interface AiConversationGenerationWorker {

    void execute(String generationPublicId, String traceId);
}
