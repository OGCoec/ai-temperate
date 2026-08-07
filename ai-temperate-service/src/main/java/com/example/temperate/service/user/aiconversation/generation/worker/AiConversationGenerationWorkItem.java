package com.example.temperate.service.user.aiconversation.generation.worker;

import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;

/**
 * 聚合 Worker 领取后读取的 Generation、不可变输入 Payload 与冻结供应商/预扣依据详情。
 */
public record AiConversationGenerationWorkItem(
        AiConversationGeneration generation,
        AiConversationGenerationPayload payload,
        AiModelUsageDetail usageDetail) {

    public AiConversationGenerationWorkItem(
            AiConversationGeneration generation,
            AiConversationGenerationPayload payload) {
        this(generation, payload, null);
    }
}
