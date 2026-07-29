package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 返回应用重启后等待管理员批准的 RabbitMQ 剩余任务摘要，并明确声明旧进程详细结果已经丢失。
 */
public record AdminMailInspectionRecoveredJobResponse(
        @Schema(
                minLength = 22,
                maxLength = 22,
                pattern = "^[A-Za-z0-9_-]{22}$",
                example = "AZ9nEjRWeJCrze8SNFZ4kA")
        String jobId,
        MailInspectionType inspectionType,
        MailInspectionJobStatus status,
        int remainingCount,
        int remainingDeliveryCount,
        int businessConcurrency,
        Instant recoveredAt,
        boolean resultHistoryLost,
        int lostResultCount,
        List<AdminMailInspectionPendingItemResponse> pendingItems) {

    public AdminMailInspectionRecoveredJobResponse {
        pendingItems = List.copyOf(pendingItems);
    }

    public static AdminMailInspectionRecoveredJobResponse from(
            MailInspectionJobSnapshot snapshot) {
        return new AdminMailInspectionRecoveredJobResponse(
                snapshot.jobId(),
                snapshot.inspectionType(),
                snapshot.status(),
                snapshot.remainingCount(),
                snapshot.remainingDeliveryCount(),
                snapshot.businessConcurrency(),
                snapshot.recoveredAt(),
                snapshot.resultHistoryLost(),
                snapshot.lostResultCount(),
                snapshot.pendingItems().stream()
                        .map(AdminMailInspectionPendingItemResponse::from)
                        .toList());
    }

    @Override
    public String toString() {
        return "AdminMailInspectionRecoveredJobResponse[status="
                + status
                + ",pendingItems=protected]";
    }
}
