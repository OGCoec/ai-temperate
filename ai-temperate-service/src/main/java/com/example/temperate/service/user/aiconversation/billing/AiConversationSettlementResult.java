package com.example.temperate.service.user.aiconversation.billing;

/**
 * 表示消息、usage、额度和详情在同一事务提交后的最终结算结果。
 */
public record AiConversationSettlementResult(
        long messageId,
        long chargedQuotaMinor,
        long settlementDeltaMinor,
        boolean reconciliationRequired) {

    public boolean requiresReconciliation() {
        return reconciliationRequired;
    }
}
