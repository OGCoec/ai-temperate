package com.example.temperate.service.user.aiconversation.reconciliation;

/**
 * 批量隔离过期预扣，并在显式开关允许时补退满足安全条件的历史系统失败记录。
 */
public interface AiConversationReconciliationService {

    /**
     * 将超过绝对流时限与安全缓冲仍未结束的预扣批量转为待对账，不自动猜测费用或退款。
     */
    int reconcileExpiredReservations();

    /**
     * 在显式开关启用时批量补退白名单内的历史系统失败，未知或用户取消记录保持不变。
     */
    int refundHistoricalSystemFailures();
}
