package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import java.util.List;

/**
 * 表示消息、额度、Usage、正式附件和会话侧栏快照全部提交后才能发送的 completed 终态数据。
 */
public record AiConversationCompletedData(
        String conversationPublicId,
        String messagePublicId,
        String usagePublicId,
        String promptTokens,
        String cachedPromptTokens,
        String completionTokens,
        String reasoningTokens,
        String chargedQuotaMinor,
        String finishReason,
        List<AiConversationAttachment> inputAttachments,
        List<AiConversationAttachment> responseAttachments,
        List<String> warnings,
        long sequence,
        AiConversationContextUsage contextUsage,
        String compactionOperationPublicId) {

    public AiConversationCompletedData {
        inputAttachments = inputAttachments == null ? List.of() : List.copyOf(inputAttachments);
        responseAttachments = responseAttachments == null ? List.of() : List.copyOf(responseAttachments);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public AiConversationCompletedData(
            String conversationPublicId,
            String messagePublicId,
            String usagePublicId,
            String promptTokens,
            String cachedPromptTokens,
            String completionTokens,
            String reasoningTokens,
            String chargedQuotaMinor,
            String finishReason,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationAttachment> responseAttachments,
            List<String> warnings) {
        this(
                conversationPublicId,
                messagePublicId,
                usagePublicId,
                promptTokens,
                cachedPromptTokens,
                completionTokens,
                reasoningTokens,
                chargedQuotaMinor,
                finishReason,
                inputAttachments,
                responseAttachments,
                warnings,
                0L,
                null,
                null);
    }

    public AiConversationCompletedData(
            String conversationPublicId,
            String messagePublicId,
            String usagePublicId,
            String promptTokens,
            String cachedPromptTokens,
            String completionTokens,
            String reasoningTokens,
            String chargedQuotaMinor,
            String finishReason) {
        this(
                conversationPublicId,
                messagePublicId,
                usagePublicId,
                promptTokens,
                cachedPromptTokens,
                completionTokens,
                reasoningTokens,
                chargedQuotaMinor,
                finishReason,
                List.of(),
                List.of(),
                List.of(),
                0L,
                null,
                null);
    }
}
