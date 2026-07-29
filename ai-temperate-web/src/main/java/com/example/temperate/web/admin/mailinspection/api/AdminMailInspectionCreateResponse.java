package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 返回 Redis 邮箱检查任务被接受时的公共 ID、初始计数和提交状态。
 */
public record AdminMailInspectionCreateResponse(
        @Schema(
                description = "固定 22 位 Base64URL 任务公共 ID。",
                minLength = 22,
                maxLength = 22,
                pattern = "^[A-Za-z0-9_-]{22}$",
                example = "AZ9nEjRWeJCrze8SNFZ4kA")
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

    public static AdminMailInspectionCreateResponse from(
            MailInspectionJobCreateResult result) {
        return new AdminMailInspectionCreateResponse(
                result.jobId(),
                result.inspectionType(),
                result.status(),
                result.requestedCount(),
                result.acceptedCount(),
                result.duplicateCount(),
                result.invalidCount(),
                result.requestedBusinessConcurrency(),
                result.appliedBusinessConcurrency(),
                result.dispatchFailedCount(),
                result.idempotencyReplayed(),
                result.submissionChunkCount(),
                result.confirmedSubmissionChunkCount(),
                result.dispatchedSubmissionChunkCount(),
                result.submissionPendingChunkCount(),
                result.submissionExpiresAt(),
                result.createdAt());
    }
}
