package com.example.temperate.service.user.aiconversation.generation.billing;

/**
 * 定义 Billing Transaction 调用既有成功结算、中断估算结算或结构化全额退款三种权威入口。
 */
public enum AiConversationGenerationBillingMode {
    COMPLETE,
    INTERRUPTED,
    REFUND_FULL
}
