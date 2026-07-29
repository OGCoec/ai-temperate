package com.example.temperate.service.admin.mailinspection.event;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.Objects;

/**
 * 承载邮件任务的非权威变更通知，只包含受保护任务哈希、修订号和固定诊断字段。
 */
public record MailInspectionJobEvent(
        int schemaVersion,
        String jobHash,
        long revision,
        MailInspectionJobEventType eventType,
        MailInspectionType inspectionType,
        Instant occurredAt) {

    public static final int SCHEMA_VERSION = 2;

    public MailInspectionJobEvent {
        if (schemaVersion != SCHEMA_VERSION || revision < 1) {
            throw new IllegalArgumentException(
                    "mail inspection event schema or revision is invalid");
        }
        Objects.requireNonNull(jobHash, "jobHash must not be null");
        if (!jobHash.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException(
                    "mail inspection event job hash is invalid");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(inspectionType, "inspectionType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
