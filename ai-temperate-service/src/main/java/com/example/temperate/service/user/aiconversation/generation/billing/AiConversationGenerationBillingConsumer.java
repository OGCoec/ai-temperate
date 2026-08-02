package com.example.temperate.service.user.aiconversation.generation.billing;

import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;

/**
 * 定义消费唯一事实终态、复用既有计费策略并在提交后发布展示终态的业务边界。
 */
public interface AiConversationGenerationBillingConsumer {

    void consume(AiConversationGenerationTerminated terminal, String traceId);
}
