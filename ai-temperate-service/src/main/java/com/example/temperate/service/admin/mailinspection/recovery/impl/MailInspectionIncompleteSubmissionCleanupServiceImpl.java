package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionIncompleteSubmissionCleanupService;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 将超过提交期限的 Redis 任务原子标记为 ABANDONED，并停止对应 Rabbit 监听器。
 *
 * <p>该流程不 purge、不扫描并 ACK 队列；孤儿 Rabbit 消息由启动一致性检查隔离，防止清理过程误删其他任务消息。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionIncompleteSubmissionCleanupServiceImpl
        implements MailInspectionIncompleteSubmissionCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MailInspectionIncompleteSubmissionCleanupServiceImpl.class);

    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionSubmissionListenerControl submissionControl;
    private final MailInspectionListenerControl workControl;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;
    private final Clock clock;

    public MailInspectionIncompleteSubmissionCleanupServiceImpl(
            AdminMailInspectionJobStore jobStore,
            MailInspectionSubmissionListenerControl submissionControl,
            MailInspectionListenerControl workControl,
            MailInspectionTypeLifecycleGuard lifecycleGuard,
            Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.submissionControl = Objects.requireNonNull(submissionControl);
        this.workControl = Objects.requireNonNull(workControl);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.submission.cleanup-interval:1m}")
    public void cleanupExpiredSubmissions() {
        for (MailInspectionRedisJobDocument document :
                jobStore.findIncompleteExpired(clock.instant())) {
            lifecycleGuard.withLock(
                    document.inspectionType(),
                    () -> cleanup(document));
        }
    }

    private void cleanup(MailInspectionRedisJobDocument document) {
        submissionControl.stop(document.inspectionType());
        workControl.stop(document.inspectionType());
        if (jobStore.markTerminal(
                document.jobId(),
                MailInspectionJobStatus.ABANDONED,
                clock.instant())) {
            LOGGER.info(
                    "event={} inspectionType={} jobRef={}",
                    "admin_mail_inspection_submission_abandoned",
                    document.inspectionType(),
                    document.jobHash().substring(0, 16));
        }
    }
}
