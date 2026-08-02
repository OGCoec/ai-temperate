package com.example.temperate.service.user.aiconversation.generation.worker;

/**
 * 表示 Generation 消息领取结果，区分新领取、领取前取消、重复终态和需要系统失败收敛的残留 RUNNING。
 */
public record AiConversationGenerationClaim(
        String outcome,
        AiConversationGenerationWorkItem workItem) {
}
