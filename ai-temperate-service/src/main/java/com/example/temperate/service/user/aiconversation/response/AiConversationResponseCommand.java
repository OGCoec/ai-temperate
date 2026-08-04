package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationRequest;
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
        AiConversationImageGenerationRequest imageGeneration,
        UUID idempotencyKey,
        AiConversationContent input) {

    public AiConversationResponseCommand {
        conversationId = conversationId == null
                ? null
                : conversationId.clone();
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
        webSearchMode = Objects.requireNonNull(webSearchMode);
    }

    /**
     * 保留普通文字调用方的既有构造方式，避免图片能力影响未选择图片模型的路径。
     */
    public AiConversationResponseCommand(
            long userId,
            String userPublicId,
            byte[] conversationId,
            String modelPublicId,
            AiConversationReasoningEffort reasoningEffort,
            AiConversationWebSearchMode webSearchMode,
            UUID idempotencyKey,
            AiConversationContent input) {
        this(
                userId,
                userPublicId,
                conversationId,
                modelPublicId,
                reasoningEffort,
                webSearchMode,
                null,
                idempotencyKey,
                input);
    }
}
