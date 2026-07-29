package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.Objects;

/**
 * 持久记录一个Submission Chunk已经完成Work消息派发，用于重启时区分缺失提交与已完成派发。
 */
public record MailInspectionDispatchMarkerMessage(
        String messageId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String traceId,
        String clientRequestId,
        String requestFingerprint,
        long jobInternalId,
        String jobId,
        MailInspectionType inspectionType,
        int chunkIndex,
        int chunkCount,
        int businessConcurrency,
        Instant createdAt,
        Instant dispatchedAt) {

    public MailInspectionDispatchMarkerMessage {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(traceId);
        Objects.requireNonNull(clientRequestId);
        Objects.requireNonNull(requestFingerprint);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(inspectionType);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(dispatchedAt);
    }

    @Override
    public String toString() {
        return "MailInspectionDispatchMarkerMessage[messageId="
                + messageId
                + ",jobId="
                + jobId
                + ",inspectionType="
                + inspectionType
                + ",chunkIndex="
                + chunkIndex
                + "]";
    }
}
