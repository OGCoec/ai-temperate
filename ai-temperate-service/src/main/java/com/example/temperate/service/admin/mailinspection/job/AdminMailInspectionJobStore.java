package com.example.temperate.service.admin.mailinspection.job;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservation;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 定义 Redis 邮箱任务的原子预留、进度、结果、终态、续租和接收闸门操作。
 *
 * <p>接口只返回不可变文档与快照，禁止调用方持有进程内可变任务状态。</p>
 */
public interface AdminMailInspectionJobStore {

    MailInspectionJobReservation reserveOrFind(
            MailInspectionRedisJobDocument candidate,
            List<MailInspectionResult> immediateResults);

    Optional<MailInspectionJobSnapshot> findSnapshot(String jobId);

    Optional<MailInspectionRedisJobDocument> findSnapshotMeta(String jobId);

    Map<String, MailInspectionRedisJobDocument> findSnapshotMetas(
            Set<String> jobIds);

    List<MailInspectionResult> findResultBatch(
            String jobId,
            int offset,
            int limit);

    Optional<MailInspectionRedisJobDocument> findByClientRequestId(
            String clientRequestId);

    Optional<MailInspectionRedisJobDocument> findActiveByType(
            MailInspectionType type);

    List<MailInspectionRedisJobDocument> findActiveJobs();

    List<MailInspectionJobSnapshot> findRecovered();

    List<MailInspectionRedisJobDocument> findIncompleteExpired(Instant now);

    boolean recordSubmissionConfirmed(
            String jobId, int chunkIndex, Instant now);

    boolean isSubmissionChunkConfirmed(
            String jobId, int chunkIndex);

    Set<Integer> confirmedSubmissionChunks(String jobId);

    boolean recordSubmissionDispatched(
            String jobId, int chunkIndex, Instant now);

    boolean isSubmissionChunkDispatched(
            String jobId, int chunkIndex);

    boolean hasResult(String jobId, int lineNumber);

    boolean claimLine(String jobId, int lineNumber, Instant now);

    boolean recordResult(
            String jobId,
            MailInspectionResult result,
            Instant now);

    boolean changeStatus(
            String jobId,
            Set<MailInspectionJobStatus> expected,
            MailInspectionJobStatus target,
            Instant now);

    boolean markTerminal(
            String jobId,
            MailInspectionJobStatus terminalStatus,
            Instant now);

    void refreshActiveLeases();

    void restorePendingJob(
            MailInspectionRedisJobDocument document,
            List<MailInspectionResult> knownResults);

    void changeAcceptanceState(
            MailInspectionType type,
            MailInspectionAcceptanceState state,
            String reason);

    default void startAccepting(MailInspectionType type) {
        changeAcceptanceState(
                type,
                MailInspectionAcceptanceState.ACCEPTING,
                "RECOVERY_COMPLETED");
    }

    default void markRecovering(MailInspectionType type) {
        changeAcceptanceState(
                type,
                MailInspectionAcceptanceState.RECOVERING,
                "RECOVERY_STARTED");
    }

    default void markUnavailable(
            MailInspectionType type, String failurePoint) {
        changeAcceptanceState(
                type,
                MailInspectionAcceptanceState.UNAVAILABLE,
                failurePoint);
    }

    default void stopAccepting(MailInspectionType type) {
        changeAcceptanceState(
                type,
                MailInspectionAcceptanceState.STOPPED,
                "STOP_REQUESTED");
    }

    void stopAllAccepting();

    MailInspectionAcceptanceState acceptanceState(
            MailInspectionType type);
}
