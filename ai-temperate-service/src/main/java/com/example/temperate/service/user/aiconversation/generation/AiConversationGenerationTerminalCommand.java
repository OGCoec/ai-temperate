package com.example.temperate.service.user.aiconversation.generation;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteredUsage;

/**
 * 承载 Worker 申请冻结唯一事实终态时的受控类型、回答快照和可选最终 Usage 证据。
 */
public record AiConversationGenerationTerminalCommand(
        byte[] generationId,
        AiConversationGenerationTerminalType terminalType,
        String terminalReason,
        String assistantText,
        String assistantAttachmentsJson,
        AiConversationMeteredUsage usage,
        String meteringEvidenceJson,
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

    public AiConversationGenerationTerminalCommand(
            byte[] generationId,
            AiConversationGenerationTerminalType terminalType,
            String terminalReason,
            String assistantText,
            String assistantAttachmentsJson,
            AiConversationMeteredUsage usage,
            String modelFinishReason,
            String upstreamRequestId,
            String traceId) {
        this(
                generationId,
                terminalType,
                terminalReason,
                assistantText,
                assistantAttachmentsJson,
                usage,
                null,
                modelFinishReason,
                upstreamRequestId,
                traceId);
    }
}
