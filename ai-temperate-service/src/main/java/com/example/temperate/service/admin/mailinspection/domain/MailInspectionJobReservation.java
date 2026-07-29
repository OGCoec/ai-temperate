package com.example.temperate.service.admin.mailinspection.domain;

import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import java.util.Objects;

/**
 * 返回原子任务预留的稳定状态和对应任务，避免并发重复POST生成多个jobId。
 */
public record MailInspectionJobReservation(
        MailInspectionJobReservationStatus status,
        MailInspectionJobState state) {

    public MailInspectionJobReservation {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    public boolean created() {
        return status == MailInspectionJobReservationStatus.CREATED;
    }
}
