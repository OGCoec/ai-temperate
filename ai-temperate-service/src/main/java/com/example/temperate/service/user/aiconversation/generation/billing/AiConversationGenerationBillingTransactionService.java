package com.example.temperate.service.user.aiconversation.generation.billing;

/**
 * 定义退款、额度、Usage、Detail 和 Generation 状态在同一 PostgreSQL 本地事务提交的边界。
 */
public interface AiConversationGenerationBillingTransactionService {

    long getOrReserveMessageId(byte[] generationId);

    AiConversationGenerationBillingResult settle(
            AiConversationGenerationBillingCommand command);
}
