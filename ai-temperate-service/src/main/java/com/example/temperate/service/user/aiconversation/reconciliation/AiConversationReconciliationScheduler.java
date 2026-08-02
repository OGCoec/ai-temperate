package com.example.temperate.service.user.aiconversation.reconciliation;

import com.example.temperate.service.user.aiconversation.generation.recovery.AiConversationGenerationRecoveryService;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按固定间隔触发过期预扣扫描、历史系统失败补退，并在异步生成启用时复用同一节拍执行消息空窗恢复。
 * 各事务边界由独立 Service 代理负责，本调度器不参与实时取消主链路。
 */
@Component
public final class AiConversationReconciliationScheduler {

    private final AiConversationReconciliationService reconciliationService;
    private final ObjectProvider<AiConversationGenerationRecoveryService> generationRecoveryService;

    public AiConversationReconciliationScheduler(
            AiConversationReconciliationService reconciliationService,
            ObjectProvider<AiConversationGenerationRecoveryService> generationRecoveryService) {
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService);
        this.generationRecoveryService = Objects.requireNonNull(
                generationRecoveryService);
    }

    @Scheduled(
            fixedDelayString = "${app.ai-conversation.reconciliation-scan-interval:1m}")
    public void scan() {
        reconciliationService.reconcileExpiredReservations();
        reconciliationService.refundHistoricalSystemFailures();
        // 异步 Generation 开关启用时复用同一个分钟级运维节拍，避免再创建高频或重复数据库扫描器。
        generationRecoveryService.ifAvailable(service -> {
            service.recoverDueGenerations();
            service.cleanupExpiredTerminalGenerations();
        });
    }
}
