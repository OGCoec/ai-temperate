package com.example.temperate.service.user.apichat.billing;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 该调度器是来每分钟触发一次外部 API 陈旧预扣恢复，事务和批量锁边界全部由独立 Service 代理承担。
 */
@Component
@ConditionalOnProperty(prefix = "app.api-key", name = "enabled", havingValue = "true")
public final class ApiChatReservationRecoveryScheduler {

    private final ApiChatReservationRecoveryService recoveryService;

    public ApiChatReservationRecoveryScheduler(
            ApiChatReservationRecoveryService recoveryService) {
        this.recoveryService = Objects.requireNonNull(recoveryService);
    }

    @Scheduled(fixedDelayString = "${app.ai-conversation.reconciliation-scan-interval:1m}")
    public void recover() {
        recoveryService.recoverExpiredReservations();
    }
}
