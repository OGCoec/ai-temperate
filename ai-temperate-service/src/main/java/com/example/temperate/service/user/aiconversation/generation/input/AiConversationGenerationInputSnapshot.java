package com.example.temperate.service.user.aiconversation.generation.input;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import java.util.List;

/**
 * 表示从现有 input_attachments JSONB 恢复出的附件列表和可选图片生成参数。
 */
public record AiConversationGenerationInputSnapshot(
        List<AiConversationAttachment> attachments,
        AiConversationImageGenerationOptions imageGeneration,
        AiConversationWebSearchMode webSearchMode) {

    public AiConversationGenerationInputSnapshot {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        webSearchMode = webSearchMode == null
                ? AiConversationWebSearchMode.OFF
                : webSearchMode;
    }

    public AiConversationGenerationInputSnapshot(
            List<AiConversationAttachment> attachments,
            AiConversationImageGenerationOptions imageGeneration) {
        this(attachments, imageGeneration, AiConversationWebSearchMode.OFF);
    }
}
