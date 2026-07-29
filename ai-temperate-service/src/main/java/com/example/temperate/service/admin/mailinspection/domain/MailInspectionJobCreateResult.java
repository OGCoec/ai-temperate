package com.example.temperate.service.admin.mailinspection.domain;

import java.time.Instant;

/**
 * 返回 Redis 邮箱检查任务已被接受后的公共 ID、初始计数和提交状态。
 */
public record MailInspectionJobCreateResult(
        String jobId,
        MailInspectionType inspectionType,
        MailInspectionJobStatus status,
        int requestedCount,
        int acceptedCount,
        int duplicateCount,
        int invalidCount,
        int requestedBusinessConcurrency,
        int appliedBusinessConcurrency,
        int dispatchFailedCount,
        boolean idempotencyReplayed,
        int submissionChunkCount,
        int confirmedSubmissionChunkCount,
        int dispatchedSubmissionChunkCount,
        int submissionPendingChunkCount,
        Instant submissionExpiresAt,
        Instant createdAt) {

    public MailInspectionJobCreateResult(
            String jobId,
            MailInspectionType inspectionType,
            MailInspectionJobStatus status,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            Instant createdAt) {
        this(
                jobId,
                inspectionType,
                status,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                4,
                4,
                0,
                false,
                0,
                0,
                0,
                0,
                null,
                createdAt);
    }
}
