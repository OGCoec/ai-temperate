package com.example.temperate.service.user.aiconversation.generation.billing;

/**
 * 表示 Terminal 消息的幂等处理结果和最终 Generation 状态。
 */
public record AiConversationGenerationBillingResult(
        boolean applied,
        String finalStatus,
        long messageId) {
}
