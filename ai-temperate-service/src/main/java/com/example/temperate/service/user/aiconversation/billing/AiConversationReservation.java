package com.example.temperate.service.user.aiconversation.billing;

import java.math.BigDecimal;

/**
 * 表示预扣事务提交后的不可变调用凭证，包括会话、usage、倍率快照和预扣额度。
 */
public record AiConversationReservation(
        byte[] conversationId,
        byte[] usageId,
        Long completedMessageId,
        int billingStatus,
        long reservedQuotaMinor,
        long estimatedPromptTokens,
        long maxOutputTokens,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        boolean newConversation,
        boolean replay) {

    public AiConversationReservation {
        conversationId = conversationId.clone();
        usageId = usageId.clone();
    }
}
