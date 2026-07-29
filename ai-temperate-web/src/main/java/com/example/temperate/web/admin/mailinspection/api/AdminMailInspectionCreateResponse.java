package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 返回邮箱检查任务被接受时的公共 ID、初始计数和建议轮询间隔。
 */
public record AdminMailInspectionCreateResponse(
        @Schema(
                description = "固定 11 位 Base64URL 任务公共 ID。",
                minLength = 11,
                maxLength = 11,
                pattern = "^[A-Za-z0-9_-]{11}$",
                example = "AAAAAAAAAAE")
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
        Instant createdAt,
        @Schema(description = "建议客户端等待的毫秒数。", example = "2000")
        long pollAfterMillis) {

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
                result.createdAt(),
                result.pollAfterMillis());
    }
}
