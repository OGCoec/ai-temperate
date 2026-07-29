package com.example.temperate.service.admin.mailinspection.job.redis;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 表示 Redis 中邮件检查任务的版本化元数据文档，不包含结果集合或任何邮箱凭证明文。
 */
public record MailInspectionRedisJobDocument(
        int schemaVersion,
        String jobId,
        String jobHash,
        MailInspectionType inspectionType,
        MailInspectionJobStatus status,
        int requestedCount,
        int acceptedCount,
        int duplicateCount,
        int invalidCount,
        int businessConcurrency,
        int completionTarget,
        String clientRequestId,
        String requestFingerprint,
        int submissionChunkCount,
        boolean recoveredAfterRestart,
        boolean resultHistoryLost,
        int lostResultCount,
        boolean resumeRequired,
        List<MailInspectionPendingItem> pendingItems,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt,
        Instant submissionExpiresAt,
        Instant recoveredAt,
        long revision) {

    public static final int SCHEMA_VERSION = 2;

    public MailInspectionRedisJobDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "mail inspection Redis job schema is unsupported");
        }
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(jobHash, "jobHash must not be null");
        Objects.requireNonNull(inspectionType, "inspectionType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!jobId.matches("^[A-Za-z0-9_-]{22}$")
                || !jobHash.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException(
                    "mail inspection Redis job identity is invalid");
        }
        if (requestedCount < 0
                || acceptedCount < 0
                || duplicateCount < 0
                || invalidCount < 0
                || completionTarget < 0
                || completionTarget > requestedCount
                || businessConcurrency < 1
                || submissionChunkCount < 0
                || lostResultCount < 0
                || revision < 0) {
            throw new IllegalArgumentException(
                    "mail inspection Redis job counters are invalid");
        }
        pendingItems = pendingItems == null ? List.of() : List.copyOf(pendingItems);
    }

    public boolean active() {
        return switch (status) {
            case DISPATCHING, AWAITING_CLIENT_RESUBMISSION, QUEUED, RUNNING,
                    AWAITING_ADMIN_RESUME, RECOVERY_FAILED -> true;
            case COMPLETED, FAILED, ABANDONED -> false;
        };
    }
}
