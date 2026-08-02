package com.example.temperate.service.user.aiconversation.generation.recovery;

/**
 * 定义事实终态持续无法结算时把 Usage 与 Generation 同事务转为 RECONCILE_REQUIRED 的边界。
 */
public interface AiConversationGenerationRecoveryService {

    int recoverDueGenerations();

    int cleanupExpiredTerminalGenerations();

    void markBillingReconcileRequired(
            byte[] generationId,
            String failureCode);
}
