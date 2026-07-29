package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.Objects;

/**
 * 定义一条有效邮箱凭证在 RabbitMQ 中的持久工作消息信封，敏感字段只允许存在于 protectedPayload。
 */
public record MailInspectionWorkMessage(
        String messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String traceId,
        String jobId,
        String jobKeyHash,
        MailInspectionType inspectionType,
        int lineNumber,
        int requestedCount,
        int acceptedCount,
        int duplicateCount,
        int invalidCount,
        int businessConcurrency,
        Instant createdAt,
        MailInspectionProtectedPayload protectedPayload,
        String clientRequestId,
        String requestFingerprint,
        Integer sourceChunkIndex) {

    public MailInspectionWorkMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(jobKeyHash, "jobKeyHash must not be null");
        Objects.requireNonNull(
                inspectionType,
                "inspectionType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(
                protectedPayload,
                "protectedPayload must not be null");
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId must not be null for work v2");
        Objects.requireNonNull(
                requestFingerprint,
                "requestFingerprint must not be null for work v2");
        if (schemaVersion != MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                || !jobId.matches("^[A-Za-z0-9_-]{22}$")
                || !jobKeyHash.matches("^[A-Za-z0-9_-]{43}$")
                || sourceChunkIndex == null
                || sourceChunkIndex < 0) {
            throw new IllegalArgumentException(
                    "work job identity, schema or source chunk is invalid");
        }
    }

    @Override
    public String toString() {
        return "MailInspectionWorkMessage[messageId="
                + messageId
                + ",jobRef="
                + jobKeyHash.substring(0, 16)
                + ",inspectionType="
                + inspectionType
                + ",lineNumber="
                + lineNumber
                + ",protectedPayload=protected]";
    }
}
