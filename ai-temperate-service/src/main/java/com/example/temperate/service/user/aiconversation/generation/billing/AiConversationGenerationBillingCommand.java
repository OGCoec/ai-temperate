package com.example.temperate.service.user.aiconversation.generation.billing;

import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;

/**
 * 承载一次事实终态在同一 PostgreSQL 事务中更新 Usage、额度、Detail 和 Generation 所需参数。
 */
public record AiConversationGenerationBillingCommand(
        byte[] generationId,
        int terminalVersion,
        AiConversationGenerationBillingMode mode,
        AiConversationSettlementCommand settlementCommand,
        String finishReason,
        String failureCode) {

    public AiConversationGenerationBillingCommand {
        generationId = generationId.clone();
    }
}
