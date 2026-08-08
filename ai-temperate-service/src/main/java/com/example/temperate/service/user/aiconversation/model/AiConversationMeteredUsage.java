package com.example.temperate.service.user.aiconversation.model;

/**
 * 表示一次模型调用可用于最终结算的强类型权威用量，禁止在 Token 与成本 ticks 之间隐式换算。
 */
public sealed interface AiConversationMeteredUsage permits
        AiConversationUsage,
        AiConversationProviderCostUsage {

    AiConversationMeteringBasis basis();
}
