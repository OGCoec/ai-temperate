package com.example.temperate.service.admin.mailinspection.domain;

import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import java.util.Objects;

/**
 * 返回 Redis 原子任务预留结果和不可变任务文档，避免并发重复 POST 生成多个 Job ID。
 */
public record MailInspectionJobReservation(
        MailInspectionJobReservationStatus status,
        MailInspectionRedisJobDocument document) {

    public MailInspectionJobReservation {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }

    public boolean created() {
        return status == MailInspectionJobReservationStatus.CREATED;
    }
}
