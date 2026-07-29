package com.example.temperate.service.admin.mailinspection.domain;

/**
 * 定义进程内邮箱检查任务从排队到结束的公开生命周期状态。
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
    ABANDONED
}
