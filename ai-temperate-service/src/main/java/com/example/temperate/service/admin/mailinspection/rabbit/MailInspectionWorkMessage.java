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
        long jobInternalId,
        String jobId,
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

    /**
     * 保留v1工作消息构造边界，使升级前已经进入Rabbit的消息仍可反序列化、恢复和消费。
     */
    public MailInspectionWorkMessage(
            String messageId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String traceId,
            long jobInternalId,
            String jobId,
            MailInspectionType inspectionType,
            int lineNumber,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt,
            MailInspectionProtectedPayload protectedPayload) {
        this(
                messageId,
                eventType,
                schemaVersion,
                occurredAt,
                traceId,
                jobInternalId,
                jobId,
                inspectionType,
                lineNumber,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                createdAt,
                protectedPayload,
                null,
                null,
                null);
    }

    public MailInspectionWorkMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(
                inspectionType,
                "inspectionType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(
                protectedPayload,
                "protectedPayload must not be null");
        if (schemaVersion >= MailInspectionRabbitNames.WORK_SCHEMA_VERSION) {
            Objects.requireNonNull(
                    clientRequestId,
                    "clientRequestId must not be null for work v2");
            Objects.requireNonNull(
                    requestFingerprint,
                    "requestFingerprint must not be null for work v2");
            if (sourceChunkIndex == null || sourceChunkIndex < 0) {
                throw new IllegalArgumentException(
                        "sourceChunkIndex must be non-negative for work v2");
            }
        }
    }

    @Override
    public String toString() {
        return "MailInspectionWorkMessage[messageId="
                + messageId
                + ",jobId="
                + jobId
                + ",inspectionType="
                + inspectionType
                + ",lineNumber="
                + lineNumber
                + ",protectedPayload=protected]";
    }
}
