package com.example.temperate.service.user.aiconversation.generation.input;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import java.util.List;

/**
 * 表示从版本化 input_attachments JSONB 恢复出的附件、媒体生成参数和联网模式快照。
 *
 * <p>快照只保存附件引用与强类型控制参数，不保存媒体 URL、二进制或临时授权信息。</p>
 */
public record AiConversationGenerationInputSnapshot(
        List<AiConversationAttachment> attachments,
        AiConversationImageGenerationOptions imageGeneration,
        AiConversationVideoGenerationOptions videoGeneration,
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
        this(attachments, imageGeneration, null, AiConversationWebSearchMode.OFF);
    }

    public AiConversationGenerationInputSnapshot(
            List<AiConversationAttachment> attachments,
            AiConversationImageGenerationOptions imageGeneration,
            AiConversationWebSearchMode webSearchMode) {
        this(attachments, imageGeneration, null, webSearchMode);
    }
}
