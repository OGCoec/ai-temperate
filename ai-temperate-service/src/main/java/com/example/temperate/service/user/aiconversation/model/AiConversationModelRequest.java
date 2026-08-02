package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import java.util.Objects;

/**
 * 承载一次上游 SSE 调用的本地模型名、最大输出限制、推理强度和不可变 Prompt 快照。
 */
public record AiConversationModelRequest(
        String modelName,
        long maxOutputTokens,
        AiConversationReasoningEffort reasoningEffort,
        AiConversationPromptSnapshot prompt) {

    public AiConversationModelRequest {
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
    }
}
