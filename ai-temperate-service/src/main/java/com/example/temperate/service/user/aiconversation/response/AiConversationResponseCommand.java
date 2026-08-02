package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.Objects;
import java.util.UUID;

/**
 * 承载一次已认证用户发送动作的可信内部参数和已映射推理强度，资源 ID 已由 Web 边界完成规范解码。
 */
public record AiConversationResponseCommand(
        long userId,
        String userPublicId,
        byte[] conversationId,
        String modelPublicId,
        AiConversationReasoningEffort reasoningEffort,
        AiConversationWebSearchMode webSearchMode,
        UUID idempotencyKey,
        AiConversationContent input) {

    public AiConversationResponseCommand {
        conversationId = conversationId == null
                ? null
                : conversationId.clone();
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
        webSearchMode = Objects.requireNonNull(webSearchMode);
    }
}
