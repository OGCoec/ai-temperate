package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSummary;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 返回 Redis 邮箱检查任务当前修订号、生命周期、计数、汇总和按行号排序的安全结果。
 */
public record AdminMailInspectionJobResponse(
        @Schema(
                minLength = 22,
                maxLength = 22,
                pattern = "^[A-Za-z0-9_-]{22}$",
                example = "AZ9nEjRWeJCrze8SNFZ4kA")
        String jobId,
        long revision,
        MailInspectionType inspectionType,
        MailInspectionJobStatus status,
        int requestedCount,
        int acceptedCount,
        int duplicateCount,
        int invalidCount,
        int processedCount,
        int runningCount,
        int queuedCount,
        boolean recoveredAfterRestart,
        boolean resumeRequired,
        boolean resultHistoryLost,
        int lostResultCount,
        int remainingCount,
        int remainingDeliveryCount,
        int businessConcurrency,
        int dispatchFailedCount,
        int submissionChunkCount,
        int confirmedSubmissionChunkCount,
        int dispatchedSubmissionChunkCount,
        int submissionPendingChunkCount,
        Instant submissionExpiresAt,
        Instant recoveredAt,
        List<AdminMailInspectionPendingItemResponse> pendingItems,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt,
        MailInspectionJobSummary summary,
        List<AdminMailInspectionResultResponse> results) {

    public AdminMailInspectionJobResponse {
        pendingItems = List.copyOf(pendingItems);
        results = List.copyOf(results);
    }

    public static AdminMailInspectionJobResponse from(
            MailInspectionJobSnapshot snapshot) {
        return new AdminMailInspectionJobResponse(
                snapshot.jobId(),
                snapshot.revision(),
                snapshot.inspectionType(),
                snapshot.status(),
                snapshot.requestedCount(),
                snapshot.acceptedCount(),
                snapshot.duplicateCount(),
                snapshot.invalidCount(),
                snapshot.processedCount(),
                snapshot.runningCount(),
                snapshot.queuedCount(),
                snapshot.recoveredAfterRestart(),
                snapshot.resumeRequired(),
                snapshot.resultHistoryLost(),
                snapshot.lostResultCount(),
                snapshot.remainingCount(),
                snapshot.remainingDeliveryCount(),
                snapshot.businessConcurrency(),
                snapshot.dispatchFailedCount(),
                snapshot.submissionChunkCount(),
                snapshot.confirmedSubmissionChunkCount(),
                snapshot.dispatchedSubmissionChunkCount(),
                snapshot.submissionPendingChunkCount(),
                snapshot.submissionExpiresAt(),
                snapshot.recoveredAt(),
                snapshot.pendingItems().stream()
                        .map(AdminMailInspectionPendingItemResponse::from)
                        .toList(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.completedAt(),
                snapshot.expiresAt(),
                snapshot.summary(),
                snapshot.results().stream()
                        .map(AdminMailInspectionResultResponse::from)
                        .toList());
    }

    @Override
    public String toString() {
        return "AdminMailInspectionJobResponse[status="
                + status
                + ",results=protected]";
    }
}
