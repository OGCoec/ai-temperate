package com.example.temperate.service.user.aiconversation.history;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 表示 PostgreSQL 中一条完整问答及其模型、Usage 和双方正式附件的只读历史投影。
 */
public record AiConversationHistoryMessage(
        String messagePublicId,
        String contentText,
        List<AiConversationAttachment> contentAttachments,
        String responseText,
        List<AiConversationAttachment> responseAttachments,
        OffsetDateTime createdAt,
        String usagePublicId,
        String modelPublicId,
        String modelName,
        String promptTokens,
        String cachedPromptTokens,
        String completionTokens,
        String reasoningTokens,
        String chargedQuotaMinor,
        String finishReason) {

    public AiConversationHistoryMessage {
        contentAttachments = List.copyOf(contentAttachments);
        responseAttachments = List.copyOf(responseAttachments);
    }
}
