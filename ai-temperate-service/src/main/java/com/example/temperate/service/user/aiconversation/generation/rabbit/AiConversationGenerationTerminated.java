package com.example.temperate.service.user.aiconversation.generation.rabbit;

/**
 * 通知 Billing Consumer 读取 PostgreSQL 中已经冻结的唯一事实终态，不携带退款或金额指令。
 */
public record AiConversationGenerationTerminated(
        String generationPublicId,
        String usagePublicId,
        String terminalType,
        String terminalReason,
        int terminalVersion) {
}
