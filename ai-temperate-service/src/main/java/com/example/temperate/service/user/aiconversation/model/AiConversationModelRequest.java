package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import java.util.Objects;

/**
 * 承载一次上游 SSE 调用的本地模型名、最大输出限制、推理强度、不可变 Prompt 快照和可选图片参数。
 */
public record AiConversationModelRequest(
        String modelName,
        long maxOutputTokens,
        AiConversationReasoningEffort reasoningEffort,
        AiConversationPromptSnapshot prompt,
        AiConversationImageGenerationOptions imageGeneration) {

    public AiConversationModelRequest {
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
    }

    public AiConversationModelRequest(
            String modelName,
            long maxOutputTokens,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationPromptSnapshot prompt) {
        this(modelName, maxOutputTokens, reasoningEffort, prompt, null);
    }
}
