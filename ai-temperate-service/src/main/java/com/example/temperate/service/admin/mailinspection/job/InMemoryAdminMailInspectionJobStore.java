package com.example.temperate.service.admin.mailinspection.job;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservation;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservationStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 使用 ConcurrentHashMap 保存短期邮箱检查任务，并以独立类型闸门隔离 Rabbit 恢复故障。
 */
@Component
public final class InMemoryAdminMailInspectionJobStore
        implements AdminMailInspectionJobStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            InMemoryAdminMailInspectionJobStore.class);

    private final AdminMailInspectionProperties properties;
    private final Clock clock;
    private final ConcurrentMap<Long, MailInspectionJobState> jobs =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> idempotencyIndex =
            new ConcurrentHashMap<>();
    private final Map<MailInspectionType, MailInspectionAcceptanceState>
            acceptanceStates = new EnumMap<>(MailInspectionType.class);

    public InMemoryAdminMailInspectionJobStore(
            AdminMailInspectionProperties properties,
            Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // 启用 Rabbit 时必须先完成启动恢复扫描，防止新任务消息与重启前的 Ready 消息混入同一类型队列。
        MailInspectionAcceptanceState initial =
                properties.rabbit().enabled()
                        ? MailInspectionAcceptanceState.RECOVERING
                        : MailInspectionAcceptanceState.UNAVAILABLE;
        for (MailInspectionType type : MailInspectionType.values()) {
            acceptanceStates.put(type, initial);
        }
        LOGGER.info(
                "event={} rabbitEnabled={} acceptanceState={}",
                "admin_mail_inspection_acceptance_initialized",
                properties.rabbit().enabled(),
                initial);
    }

    @Override
    public synchronized void create(MailInspectionJobState state) {
        cleanupExpiredAt(clock.instant());
        MailInspectionAcceptanceState acceptanceState =
                acceptanceStates.get(state.type());
        if (acceptanceState != MailInspectionAcceptanceState.ACCEPTING) {
            // 只记录固定状态，不记录任务请求、邮箱或凭证；具体恢复失败阶段由恢复协调器日志提供。
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "acceptanceState={} rabbitEnabled={}",
                    "admin_mail_inspection_create_rejected",
                    state.type(),
                    "JOB_STORE_NOT_ACCEPTING",
                    acceptanceState,
                    properties.rabbit().enabled());
            throw unavailable();
        }
        long active = jobs.values().stream()
                .filter(MailInspectionJobState::isActive)
                .count();
        long sameType = jobs.values().stream()
                .filter(MailInspectionJobState::isActive)
                .filter(existing -> existing.type() == state.type())
                .count();
        if (active >= properties.job().maxActiveJobs()
                || sameType >= properties.job().maxActiveJobsPerType()) {
            throw conflict();
        }
        MailInspectionJobState previous =
                jobs.putIfAbsent(state.internalId(), state);
        if (previous != null) {
            throw new IllegalStateException("duplicate mail inspection job ID");
        }
    }

    @Override
    public synchronized MailInspectionJobReservation reserveOrFind(
            String clientRequestId,
            MailInspectionRequestFingerprint requestFingerprint,
            MailInspectionJobState candidateState) {
        cleanupExpiredAt(clock.instant());
        Long existingId = idempotencyIndex.get(clientRequestId);
        if (existingId != null) {
            MailInspectionJobState existing = jobs.get(existingId);
            if (existing != null) {
                MailInspectionJobReservationStatus status =
                        existing.matchesRequestFingerprint(requestFingerprint)
                                ? MailInspectionJobReservationStatus.REPLAYED
                                : MailInspectionJobReservationStatus.FINGERPRINT_CONFLICT;
                return new MailInspectionJobReservation(status, existing);
            }
            idempotencyIndex.remove(clientRequestId, existingId);
        }
        try {
            create(candidateState);
        } catch (AdminException exception) {
            if (exception.code()
                    != AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_CONFLICT) {
                throw exception;
            }
            return new MailInspectionJobReservation(
                    MailInspectionJobReservationStatus.TYPE_CAPACITY_CONFLICT,
                    candidateState);
        }
        idempotencyIndex.put(clientRequestId, candidateState.internalId());
        return new MailInspectionJobReservation(
                MailInspectionJobReservationStatus.CREATED,
                candidateState);
    }

    @Override
    public synchronized void restore(MailInspectionJobState state) {
        cleanupExpiredAt(clock.instant());
        MailInspectionJobState sameType = jobs.values().stream()
                .filter(MailInspectionJobState::isActive)
                .filter(existing -> existing.type() == state.type())
                .findFirst()
                .orElse(null);
        if (sameType != null
                && sameType.internalId() != state.internalId()) {
            throw conflict();
        }
        jobs.putIfAbsent(state.internalId(), state);
        if (state.clientRequestId() != null) {
            Long previous = idempotencyIndex.putIfAbsent(
                    state.clientRequestId(),
                    state.internalId());
            if (previous != null && previous != state.internalId()) {
                throw conflict();
            }
        }
    }

    @Override
    public Optional<MailInspectionJobState> find(long internalId) {
        cleanupExpiredAt(clock.instant());
        return Optional.ofNullable(jobs.get(internalId));
    }

    @Override
    public Optional<MailInspectionJobState> findByClientRequestId(
            String clientRequestId) {
        cleanupExpiredAt(clock.instant());
        Long internalId = idempotencyIndex.get(clientRequestId);
        return internalId == null
                ? Optional.empty()
                : Optional.ofNullable(jobs.get(internalId));
    }

    @Override
    public Optional<MailInspectionJobState> findActiveByType(
            MailInspectionType type) {
        cleanupExpiredAt(clock.instant());
        return jobs.values().stream()
                .filter(MailInspectionJobState::isActive)
                .filter(state -> state.type() == type)
                .findFirst();
    }

    @Override
    public List<MailInspectionJobSnapshot> findRecovered() {
        cleanupExpiredAt(clock.instant());
        return jobs.values().stream()
                .filter(MailInspectionJobState::recoveredAfterRestart)
                .filter(state -> state.status()
                        == MailInspectionJobStatus.AWAITING_ADMIN_RESUME
                        || state.status()
                                == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION
                        || state.status()
                                == MailInspectionJobStatus.RECOVERY_FAILED)
                .map(MailInspectionJobState::snapshot)
                .sorted(Comparator.comparing(
                        MailInspectionJobSnapshot::createdAt))
                .toList();
    }

    @Override
    public List<MailInspectionJobState> findIncompleteExpired(Instant now) {
        cleanupExpiredAt(now);
        return jobs.values().stream()
                .filter(state -> state.incompleteSubmissionExpiredAt(now))
                .sorted(Comparator.comparing(
                        state -> state.snapshot().createdAt()))
                .toList();
    }

    @Override
    public synchronized void startAccepting(MailInspectionType type) {
        MailInspectionAcceptanceState previous =
                acceptanceStates.put(
                        type,
                        MailInspectionAcceptanceState.ACCEPTING);
        LOGGER.info(
                "event={} inspectionType={} previousState={} currentState={} "
                        + "reason={}",
                "admin_mail_inspection_acceptance_changed",
                type,
                previous,
                MailInspectionAcceptanceState.ACCEPTING,
                "RECOVERY_COMPLETED");
    }

    @Override
    public synchronized void markRecovering(MailInspectionType type) {
        changeAcceptance(
                type,
                MailInspectionAcceptanceState.RECOVERING,
                "RECOVERY_STARTED",
                false);
    }

    @Override
    public synchronized void markUnavailable(
            MailInspectionType type,
            String failurePoint) {
        changeAcceptance(
                type,
                MailInspectionAcceptanceState.UNAVAILABLE,
                failurePoint,
                true);
    }

    @Override
    public synchronized void stopAccepting(MailInspectionType type) {
        changeAcceptance(
                type,
                MailInspectionAcceptanceState.STOPPED,
                "STOP_REQUESTED",
                true);
    }

    @Override
    public synchronized void stopAllAccepting() {
        for (MailInspectionType type : MailInspectionType.values()) {
            stopAccepting(type);
        }
    }

    @Override
    public synchronized MailInspectionAcceptanceState acceptanceState(
            MailInspectionType type) {
        return acceptanceStates.get(type);
    }

    @Override
    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.job.cleanup-interval:PT1M}")
    public void cleanupExpired() {
        cleanupExpiredAt(clock.instant());
    }

    private void cleanupExpiredAt(Instant now) {
        jobs.entrySet().removeIf(entry -> {
            if (!entry.getValue().isExpiredAt(now)) {
                return false;
            }
            String clientRequestId = entry.getValue().clientRequestId();
            if (clientRequestId != null) {
                idempotencyIndex.remove(clientRequestId, entry.getKey());
            }
            return true;
        });
    }

    private static AdminException conflict() {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_CONFLICT,
                "mail inspection job capacity reached");
    }

    private static AdminException unavailable() {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                "mail inspection job service is stopping");
    }

    private void changeAcceptance(
            MailInspectionType type,
            MailInspectionAcceptanceState next,
            String reason,
            boolean warning) {
        MailInspectionAcceptanceState previous =
                acceptanceStates.put(type, next);
        if (warning) {
            LOGGER.warn(
                    "event={} inspectionType={} previousState={} "
                            + "currentState={} reason={}",
                    "admin_mail_inspection_acceptance_changed",
                    type,
                    previous,
                    next,
                    reason);
            return;
        }
        LOGGER.info(
                "event={} inspectionType={} previousState={} "
                        + "currentState={} reason={}",
                "admin_mail_inspection_acceptance_changed",
                type,
                previous,
                next,
                reason);
    }
}
