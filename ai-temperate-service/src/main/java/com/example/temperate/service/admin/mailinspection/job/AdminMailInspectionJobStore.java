package com.example.temperate.service.admin.mailinspection.job;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservation;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;

/**
 * 定义进程内邮箱检查任务的容量、幂等预留、恢复快照与按检查类型接收闸门。
 */
public interface AdminMailInspectionJobStore {

    void create(MailInspectionJobState state);

    MailInspectionJobReservation reserveOrFind(
            String clientRequestId,
            MailInspectionRequestFingerprint requestFingerprint,
            MailInspectionJobState candidateState);

    void restore(MailInspectionJobState state);

    Optional<MailInspectionJobState> find(long internalId);

    Optional<MailInspectionJobState> findByClientRequestId(
            String clientRequestId);

    Optional<MailInspectionJobState> findActiveByType(
            MailInspectionType type);

    List<MailInspectionJobSnapshot> findRecovered();

    List<MailInspectionJobState> findIncompleteExpired(Instant now);

    void startAccepting(MailInspectionType type);

    void markRecovering(MailInspectionType type);

    void markUnavailable(
            MailInspectionType type,
            String failurePoint);

    void stopAccepting(MailInspectionType type);

    void stopAllAccepting();

    MailInspectionAcceptanceState acceptanceState(
            MailInspectionType type);

    void cleanupExpired();
}
