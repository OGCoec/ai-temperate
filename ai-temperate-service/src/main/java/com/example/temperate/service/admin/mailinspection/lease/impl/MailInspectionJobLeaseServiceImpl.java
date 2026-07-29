package com.example.temperate.service.admin.mailinspection.lease.impl;

import com.example.temperate.service.admin.mailinspection.diagnostic.MailInspectionDiagnosticOperation;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.lease.MailInspectionJobLeaseService;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 使用后端定时心跳续签 Redis 活动租约，使浏览器前后台切换不会影响任务生存期。
 */
@Service
public final class MailInspectionJobLeaseServiceImpl
        implements MailInspectionJobLeaseService {

    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionListenerControl workListenerControl;
    private final MailInspectionSubmissionListenerControl
            submissionListenerControl;

    public MailInspectionJobLeaseServiceImpl(
            AdminMailInspectionJobStore jobStore,
            MailInspectionListenerControl workListenerControl,
            MailInspectionSubmissionListenerControl
                    submissionListenerControl) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.workListenerControl = Objects.requireNonNull(
                workListenerControl);
        this.submissionListenerControl = Objects.requireNonNull(
                submissionListenerControl);
    }

    @Override
    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.job.lease-heartbeat-interval:30s}")
    @MailInspectionDiagnosticOperation("lease-refresh")
    public void refreshActiveLeases() {
        try {
            jobStore.refreshActiveLeases();
        } catch (RuntimeException exception) {
            // 租约无法刷新意味着 Redis 权威边界不可用；先停消费者，确保现有 Rabbit 消息不会在无状态保护下被 ACK。
            stopConsumersBestEffort();
            throw exception;
        }
    }

    private void stopConsumersBestEffort() {
        try {
            submissionListenerControl.stopAll();
        } catch (RuntimeException ignored) {
            // 一个监听器控制面失败不能阻断另一个控制面关闭，也不能掩盖触发停机的 Redis 原始异常。
        }
        try {
            workListenerControl.stopAll();
        } catch (RuntimeException ignored) {
            // Redis 原始异常由调用方继续记录；下一次恢复检查仍会保持接收闸门关闭。
        }
    }
}
