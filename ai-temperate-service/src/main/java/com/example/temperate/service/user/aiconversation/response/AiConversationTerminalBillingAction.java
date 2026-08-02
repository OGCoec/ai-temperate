package com.example.temperate.service.user.aiconversation.response;

/**
 * 定义模型流终止后唯一允许执行的额度终态动作，避免用布尔值混淆退款、结算和待对账。
 */
public enum AiConversationTerminalBillingAction {

    REFUND_FULL,
    SETTLE_REPORTED_USAGE,
    SETTLE_ESTIMATED_CLIENT_CANCEL,
    RECONCILE_REQUIRED
}
