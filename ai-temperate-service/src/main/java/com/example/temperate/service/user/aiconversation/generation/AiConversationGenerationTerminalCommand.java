package com.example.temperate.service.user.aiconversation.generation;

import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;

/**
 * 承载 Worker 申请冻结唯一事实终态时的受控类型、回答快照和可选最终 Usage 证据。
 */
public record AiConversationGenerationTerminalCommand(
        byte[] generationId,
        AiConversationGenerationTerminalType terminalType,
        String terminalReason,
        String assistantText,
        String assistantAttachmentsJson,
        AiConversationUsage usage,
        String modelFinishReason,
        String upstreamRequestId,
        String traceId) {

    public AiConversationGenerationTerminalCommand {
        generationId = generationId.clone();
        assistantText = assistantText == null ? "" : assistantText;
        assistantAttachmentsJson = assistantAttachmentsJson == null
                ? "[]"
                : assistantAttachmentsJson;
    }
}
