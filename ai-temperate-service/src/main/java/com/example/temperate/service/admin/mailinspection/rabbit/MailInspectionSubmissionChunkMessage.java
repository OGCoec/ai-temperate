package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.Objects;

/**
 * 承载已确认持久化的一段批量邮箱凭证，敏感字段只允许存在于AES-GCM保护载荷中。
 */
public record MailInspectionSubmissionChunkMessage(
        String messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String traceId,
        String clientRequestId,
        String requestFingerprint,
        String jobId,
        String jobKeyHash,
        MailInspectionType inspectionType,
        int chunkIndex,
        int chunkCount,
        int requestedCount,
        int acceptedCount,
        int duplicateCount,
        int invalidCount,
        int businessConcurrency,
        Instant createdAt,
        MailInspectionProtectedPayload protectedPayload) {

    public MailInspectionSubmissionChunkMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(traceId);
        Objects.requireNonNull(clientRequestId);
        Objects.requireNonNull(requestFingerprint);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(jobKeyHash);
        Objects.requireNonNull(inspectionType);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(protectedPayload);
        if (chunkIndex < 0 || chunkCount < 1 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("submission chunk index is invalid");
        }
        if (schemaVersion != MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION
                || !jobId.matches("^[A-Za-z0-9_-]{22}$")
                || !jobKeyHash.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException(
                    "submission job identity or schema is invalid");
        }
    }

    @Override
    public String toString() {
        return "MailInspectionSubmissionChunkMessage[messageId="
                + messageId
                + ",jobRef="
                + jobKeyHash.substring(0, 16)
                + ",inspectionType="
                + inspectionType
                + ",chunkIndex="
                + chunkIndex
                + ",chunkCount="
                + chunkCount
                + ",protectedPayload=protected]";
    }
}
