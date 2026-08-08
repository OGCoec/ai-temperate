package com.example.temperate.service.user.aiconversation.billing;

/**
 * 定义模型流成功、明确未计费失败和 usage 不确定中断时的最终短事务结算边界。
 */
public interface AiConversationSettlementService {

    long reserveMessageId();

    AiConversationSettlementResult complete(AiConversationSettlementCommand command);

    AiConversationSettlementResult settleInterrupted(
            AiConversationSettlementCommand command);

    AiConversationSettlementResult completeReconcile(
            AiConversationSettlementCommand command,
            String failureCode);

    void refundFailed(byte[] usageId, String failureCode);

    void refundFailed(
            byte[] usageId,
            String finishReason,
            String failureCode);

    void markReconcileRequired(byte[] usageId, String failureCode);
}
