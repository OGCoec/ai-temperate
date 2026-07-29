package com.example.temperate.service.admin.mailinspection.domain;

/**
 * 定义 Redis 邮箱检查任务从持久提交到终态保留的公开生命周期状态。
 */
public enum MailInspectionJobStatus {
    DISPATCHING,
    AWAITING_CLIENT_RESUBMISSION,
    QUEUED,
    RUNNING,
    AWAITING_ADMIN_RESUME,
    RECOVERY_FAILED,
    COMPLETED,
    FAILED,
    ABANDONED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == ABANDONED;
    }

    public boolean active() {
        return !terminal();
    }
}
