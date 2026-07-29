package com.example.temperate.service.admin.mailinspection.domain;

import java.time.Instant;
import java.util.List;

/**
 * 表示可由统一查询接口读取的邮箱检查任务快照，任务内部可变状态不会跨越该边界。
 */
public record MailInspectionJobSnapshot(
        String jobId,
        MailInspectionType inspectionType,
        MailInspectionJobStatus status,
        int requestedCount,
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
        List<MailInspectionPendingItem> pendingItems,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt,
        MailInspectionJobSummary summary,
        List<MailInspectionResult> results) {

    public MailInspectionJobSnapshot(
            String jobId,
            MailInspectionType inspectionType,
            MailInspectionJobStatus status,
            int requestedCount,
            int processedCount,
            int runningCount,
            int queuedCount,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant expiresAt,
            MailInspectionJobSummary summary,
            List<MailInspectionResult> results) {
        this(
                jobId,
                inspectionType,
                status,
                requestedCount,
                processedCount,
                runningCount,
                queuedCount,
                false,
                false,
                false,
                0,
                Math.max(0, runningCount + queuedCount),
                Math.max(0, runningCount + queuedCount),
                4,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                List.of(),
                createdAt,
                startedAt,
                completedAt,
                expiresAt,
                summary,
                results);
    }

    public MailInspectionJobSnapshot {
        pendingItems = pendingItems == null
                ? List.of()
                : List.copyOf(pendingItems);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
