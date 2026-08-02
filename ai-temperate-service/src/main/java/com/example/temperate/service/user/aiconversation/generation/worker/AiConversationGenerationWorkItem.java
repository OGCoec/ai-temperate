package com.example.temperate.service.user.aiconversation.generation.worker;

import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;

/**
 * 聚合 Worker 领取后读取的 Generation 控制行和不可变输入 Payload。
 */
public record AiConversationGenerationWorkItem(
        AiConversationGeneration generation,
        AiConversationGenerationPayload payload) {
}
