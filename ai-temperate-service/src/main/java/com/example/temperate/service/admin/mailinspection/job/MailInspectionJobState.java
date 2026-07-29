package com.example.temperate.service.admin.mailinspection.job;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSummary;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存单个邮箱检查任务的进程内安全状态，并以 lineNumber 幂等隔离 RabbitMQ 重投递和异步完成竞态。
 *
 * <p>正常任务仅保存脱敏结果；恢复任务额外保存脱敏待处理行。密码、clientId、refresh token、密文和邮件正文均不进入该状态。</p>
 */
public final class MailInspectionJobState {

    private final long internalId;
    private final String publicId;
    private final MailInspectionType type;
    private final int requestedCount;
    private final int acceptedCount;
    private final int duplicateCount;
    private final int invalidCount;
    private final int businessConcurrency;
    private final int completionTarget;
    private final String clientRequestId;
    private final MailInspectionRequestFingerprint requestFingerprint;
    private final int submissionChunkCount;
    private final Instant createdAt;
    private final boolean recoveredAfterRestart;
    private final boolean resultHistoryLost;
    private final int lostResultCount;
    private final AtomicReference<MailInspectionJobStatus> status;
    private final AtomicInteger processedCount = new AtomicInteger();
    private final AtomicInteger runningCount = new AtomicInteger();
    private final AtomicInteger queuedCount;
    private final AtomicInteger dispatchFailedCount = new AtomicInteger();
    private final ConcurrentMap<Integer, MailInspectionResult> results =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Boolean> inFlight =
            new ConcurrentHashMap<>();
    private final Set<Integer> confirmedChunkIndexes =
            ConcurrentHashMap.newKeySet();
    private final Set<Integer> dispatchedChunkIndexes =
            ConcurrentHashMap.newKeySet();

    private volatile boolean resumeRequired;
    private volatile Instant recoveredAt;
    private volatile List<MailInspectionPendingItem> pendingItems;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile Instant expiresAt;
    private volatile Instant lastSubmissionActivityAt;
    private volatile Instant submissionExpiresAt;
    private boolean incompleteCleanupClaimed;

    public MailInspectionJobState(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            Instant createdAt,
            List<MailInspectionResult> immediateResults) {
        this(
                internalId,
                publicId,
                type,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                4,
                createdAt,
                immediateResults);
    }

    public MailInspectionJobState(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt,
            List<MailInspectionResult> immediateResults) {
        this(
                internalId,
                publicId,
                type,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                requestedCount,
                null,
                null,
                0,
                createdAt,
                false,
                false,
                0,
                false,
                null,
                List.of(),
                MailInspectionJobStatus.QUEUED,
                acceptedCount,
                immediateResults);
    }

    private MailInspectionJobState(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            int completionTarget,
            String clientRequestId,
            MailInspectionRequestFingerprint requestFingerprint,
            int submissionChunkCount,
            Instant createdAt,
            boolean recoveredAfterRestart,
            boolean resultHistoryLost,
            int lostResultCount,
            boolean resumeRequired,
            Instant recoveredAt,
            List<MailInspectionPendingItem> pendingItems,
            MailInspectionJobStatus initialStatus,
            int initialQueuedCount,
            List<MailInspectionResult> immediateResults) {
        if (internalId <= 0) {
            throw new IllegalArgumentException(
                    "internalId must be positive");
        }
        if (businessConcurrency < 1 || businessConcurrency > 64) {
            throw new IllegalArgumentException(
                    "businessConcurrency must be between 1 and 64");
        }
        this.internalId = internalId;
        this.publicId = Objects.requireNonNull(publicId);
        this.type = Objects.requireNonNull(type);
        this.requestedCount = requestedCount;
        this.acceptedCount = acceptedCount;
        this.duplicateCount = duplicateCount;
        this.invalidCount = invalidCount;
        this.businessConcurrency = businessConcurrency;
        this.completionTarget = completionTarget;
        this.clientRequestId = clientRequestId;
        this.requestFingerprint = requestFingerprint;
        this.submissionChunkCount = submissionChunkCount;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.recoveredAfterRestart = recoveredAfterRestart;
        this.resultHistoryLost = resultHistoryLost;
        this.lostResultCount = lostResultCount;
        this.resumeRequired = resumeRequired;
        this.recoveredAt = recoveredAt;
        this.pendingItems = List.copyOf(pendingItems);
        this.status = new AtomicReference<>(initialStatus);
        this.queuedCount = new AtomicInteger(initialQueuedCount);
        for (MailInspectionResult result : immediateResults) {
            results.put(result.lineNumber(), result);
        }
        this.processedCount.set(results.size());
        this.lastSubmissionActivityAt = createdAt;
    }

    /**
     * 创建一个尚处于Rabbit持久提交阶段的任务，原始凭证不会进入该状态对象。
     */
    public static MailInspectionJobState submitting(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            String clientRequestId,
            MailInspectionRequestFingerprint requestFingerprint,
            int submissionChunkCount,
            Instant createdAt,
            Duration incompleteRetention,
            List<MailInspectionResult> immediateResults) {
        MailInspectionJobState state = new MailInspectionJobState(
                internalId,
                publicId,
                type,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                requestedCount,
                Objects.requireNonNull(clientRequestId),
                Objects.requireNonNull(requestFingerprint),
                submissionChunkCount,
                createdAt,
                false,
                false,
                0,
                false,
                null,
                List.of(),
                MailInspectionJobStatus.DISPATCHING,
                acceptedCount,
                immediateResults);
        state.touchSubmission(createdAt, incompleteRetention);
        return state;
    }

    /**
     * 从 Rabbit Ready 消息重建暂停任务；旧进程结果只计入 lostResultCount，不伪造任何已完成结果。
     */
    public static MailInspectionJobState recovered(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt,
            Instant recoveredAt,
            List<MailInspectionPendingItem> pendingItems) {
        List<MailInspectionPendingItem> safeItems =
                List.copyOf(pendingItems);
        return new MailInspectionJobState(
                internalId,
                publicId,
                type,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                safeItems.size(),
                null,
                null,
                0,
                createdAt,
                true,
                true,
                Math.max(0, requestedCount - safeItems.size()),
                true,
                recoveredAt,
                safeItems,
                MailInspectionJobStatus.AWAITING_ADMIN_RESUME,
                safeItems.size(),
                List.of());
    }

    /**
     * 从 Submission、Marker 与 Work 三类持久消息联合重建任务；只有索引并集完整时才允许进入管理员批准状态。
     */
    public static MailInspectionJobState recoveredSubmission(
            long internalId,
            String publicId,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            String clientRequestId,
            MailInspectionRequestFingerprint requestFingerprint,
            int submissionChunkCount,
            Set<Integer> confirmedChunkIndexes,
            Set<Integer> dispatchedChunkIndexes,
            Instant createdAt,
            Instant recoveredAt,
            Duration incompleteRetention,
            List<MailInspectionPendingItem> pendingItems,
            boolean submissionComplete) {
        List<MailInspectionPendingItem> safeItems = List.copyOf(pendingItems);
        MailInspectionJobStatus initialStatus = submissionComplete
                ? MailInspectionJobStatus.AWAITING_ADMIN_RESUME
                : MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION;
        MailInspectionJobState state = new MailInspectionJobState(
                internalId,
                publicId,
                type,
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                safeItems.size(),
                clientRequestId,
                requestFingerprint,
                submissionChunkCount,
                createdAt,
                true,
                true,
                Math.max(0, requestedCount - safeItems.size()),
                submissionComplete,
                recoveredAt,
                safeItems,
                initialStatus,
                safeItems.size(),
                List.of());
        state.confirmedChunkIndexes.addAll(confirmedChunkIndexes);
        state.dispatchedChunkIndexes.addAll(dispatchedChunkIndexes);
        state.touchSubmission(recoveredAt, incompleteRetention);
        return state;
    }

    public long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public MailInspectionType type() {
        return type;
    }

    public int requestedCount() {
        return requestedCount;
    }

    public int acceptedCount() {
        return acceptedCount;
    }

    public int duplicateCount() {
        return duplicateCount;
    }

    public int invalidCount() {
        return invalidCount;
    }

    public int businessConcurrency() {
        return businessConcurrency;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String clientRequestId() {
        return clientRequestId;
    }

    public MailInspectionRequestFingerprint requestFingerprint() {
        return requestFingerprint;
    }

    public int submissionChunkCount() {
        return submissionChunkCount;
    }

    public boolean matchesRequestFingerprint(
            MailInspectionRequestFingerprint candidate) {
        return requestFingerprint != null
                && requestFingerprint.equals(candidate);
    }

    public boolean recoveredAfterRestart() {
        return recoveredAfterRestart;
    }

    public MailInspectionJobStatus status() {
        return status.get();
    }

    public boolean isAwaitingResume() {
        return status.get()
                == MailInspectionJobStatus.AWAITING_ADMIN_RESUME;
    }

    public boolean isActive() {
        MailInspectionJobStatus value = status.get();
        return value == MailInspectionJobStatus.DISPATCHING
                || value
                        == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION
                || value == MailInspectionJobStatus.QUEUED
                || value == MailInspectionJobStatus.RUNNING
                || value == MailInspectionJobStatus.AWAITING_ADMIN_RESUME
                || value == MailInspectionJobStatus.RECOVERY_FAILED;
    }

    public boolean isExpiredAt(Instant now) {
        Instant deadline = expiresAt;
        return deadline != null && !deadline.isAfter(now);
    }

    public boolean hasResult(int lineNumber) {
        return results.containsKey(lineNumber);
    }

    /**
     * 只有正常创建或管理员显式批准恢复时才允许推进到 RUNNING；恢复待批准状态不会被启动扫描器自动推进。
     */
    public synchronized boolean markRunning(Instant now) {
        MailInspectionJobStatus current = status.get();
        if (current != MailInspectionJobStatus.QUEUED
                && current
                        != MailInspectionJobStatus.AWAITING_ADMIN_RESUME) {
            return false;
        }
        if (startedAt == null) {
            startedAt = now;
        }
        resumeRequired = false;
        status.set(MailInspectionJobStatus.RUNNING);
        return true;
    }

    public synchronized boolean markQueued() {
        MailInspectionJobStatus current = status.get();
        if (current != MailInspectionJobStatus.DISPATCHING
                && current
                        != MailInspectionJobStatus.AWAITING_ADMIN_RESUME) {
            return false;
        }
        status.set(MailInspectionJobStatus.QUEUED);
        return true;
    }

    public synchronized void markAwaitingClientResubmission(
            Instant now,
            Duration incompleteRetention) {
        if (!isActive()) {
            return;
        }
        touchSubmission(now, incompleteRetention);
        status.set(MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION);
    }

    public synchronized boolean markDispatching(
            Instant now,
            Duration incompleteRetention) {
        if (!isActive() || incompleteCleanupClaimed) {
            return false;
        }
        touchSubmission(now, incompleteRetention);
        status.set(MailInspectionJobStatus.DISPATCHING);
        return true;
    }

    public boolean confirmSubmissionChunk(
            int chunkIndex,
            Instant now,
            Duration incompleteRetention) {
        validateChunkIndex(chunkIndex);
        boolean inserted = confirmedChunkIndexes.add(chunkIndex);
        touchSubmission(now, incompleteRetention);
        return inserted;
    }

    public boolean isSubmissionChunkConfirmed(int chunkIndex) {
        validateChunkIndex(chunkIndex);
        return confirmedChunkIndexes.contains(chunkIndex);
    }

    public boolean markSubmissionChunkDispatched(int chunkIndex) {
        validateChunkIndex(chunkIndex);
        return dispatchedChunkIndexes.add(chunkIndex);
    }

    public boolean isSubmissionChunkDispatched(int chunkIndex) {
        return dispatchedChunkIndexes.contains(chunkIndex);
    }

    public boolean allSubmissionChunksConfirmed() {
        return confirmedChunkIndexes.size() >= submissionChunkCount;
    }

    public boolean allSubmissionChunksDispatched() {
        return dispatchedChunkIndexes.size() >= submissionChunkCount;
    }

    public int confirmedSubmissionChunkCount() {
        return confirmedChunkIndexes.size();
    }

    public int dispatchedSubmissionChunkCount() {
        return dispatchedChunkIndexes.size();
    }

    public Instant submissionExpiresAt() {
        return submissionExpiresAt;
    }

    public boolean incompleteSubmissionExpiredAt(Instant now) {
        MailInspectionJobStatus current = status.get();
        return (current == MailInspectionJobStatus.DISPATCHING
                        || current
                                == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION)
                && submissionExpiresAt != null
                && !submissionExpiresAt.isAfter(now);
    }

    /**
     * 以任务内原子声明替代跨异步线程持有可重入锁；清理和客户端补交最多只有一方能取得后续 Rabbit 操作权。
     */
    public synchronized boolean tryClaimIncompleteCleanup(Instant now) {
        if (incompleteCleanupClaimed
                || !incompleteSubmissionExpiredAt(now)) {
            return false;
        }
        incompleteCleanupClaimed = true;
        return true;
    }

    public synchronized void markAwaitingAdminResume() {
        if (status.get() == MailInspectionJobStatus.COMPLETED
                || status.get() == MailInspectionJobStatus.FAILED) {
            return;
        }
        resumeRequired = true;
        status.set(MailInspectionJobStatus.AWAITING_ADMIN_RESUME);
    }

    public synchronized void markRecoveryFailed() {
        incompleteCleanupClaimed = false;
        resumeRequired = true;
        status.set(MailInspectionJobStatus.RECOVERY_FAILED);
    }

    /**
     * Rabbit 可能在 ACK 丢失后重新投递同一行；inFlight 只允许第一次投递改变 running/queued 计数。
     */
    public boolean itemStarted(int lineNumber) {
        if (hasResult(lineNumber)
                || inFlight.putIfAbsent(lineNumber, Boolean.TRUE)
                        != null) {
            return false;
        }
        queuedCount.updateAndGet(value -> Math.max(0, value - 1));
        runningCount.incrementAndGet();
        return true;
    }

    public boolean recordResult(MailInspectionResult result) {
        MailInspectionResult previous =
                results.putIfAbsent(result.lineNumber(), result);
        if (previous != null) {
            return false;
        }
        processedCount.incrementAndGet();
        if (inFlight.remove(result.lineNumber()) != null) {
            runningCount.updateAndGet(value -> Math.max(0, value - 1));
        }
        return true;
    }

    public boolean recordDispatchFailure(
            MailInspectionResult result) {
        boolean inserted = recordResult(result);
        if (inserted) {
            queuedCount.updateAndGet(value -> Math.max(0, value - 1));
            dispatchFailedCount.incrementAndGet();
        }
        return inserted;
    }

    public boolean hasCompletedWork() {
        return processedCount.get() >= completionTarget;
    }

    public synchronized void complete(
            Instant now,
            Duration retention) {
        if (!isActive()) {
            return;
        }
        queuedCount.set(0);
        runningCount.set(0);
        completedAt = now;
        expiresAt = now.plus(retention);
        resumeRequired = false;
        pendingItems = List.of();
        status.set(MailInspectionJobStatus.COMPLETED);
    }

    public synchronized void fail(
            Instant now,
            Duration retention) {
        if (!isActive()) {
            return;
        }
        queuedCount.set(0);
        runningCount.set(0);
        completedAt = now;
        expiresAt = now.plus(retention);
        resumeRequired = false;
        pendingItems = List.of();
        status.set(MailInspectionJobStatus.FAILED);
    }

    public synchronized void abandon(
            Instant now,
            Duration retention) {
        incompleteCleanupClaimed = false;
        queuedCount.set(0);
        runningCount.set(0);
        completedAt = now;
        expiresAt = now.plus(retention);
        resumeRequired = false;
        pendingItems = List.of();
        status.set(MailInspectionJobStatus.ABANDONED);
    }

    private synchronized void touchSubmission(
            Instant now,
            Duration incompleteRetention) {
        lastSubmissionActivityAt = Objects.requireNonNull(now);
        submissionExpiresAt = now.plus(incompleteRetention);
    }

    private void validateChunkIndex(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= submissionChunkCount) {
            throw new IllegalArgumentException(
                    "mail inspection submission chunk index is invalid");
        }
    }

    /**
     * 复制并按 lineNumber 排序公开结果，同时只输出恢复场景所需的脱敏行，不暴露 Rabbit 消息或受保护载荷。
     */
    public MailInspectionJobSnapshot snapshot() {
        List<MailInspectionResult> ordered = results.values().stream()
                .sorted(Comparator.comparingInt(
                        MailInspectionResult::lineNumber))
                .toList();
        EnumMap<MailInspectionResultStatus, Integer> counts =
                new EnumMap<>(MailInspectionResultStatus.class);
        ordered.forEach(result ->
                counts.merge(result.status(), 1, Integer::sum));
        int remaining =
                Math.max(0, queuedCount.get() + runningCount.get());
        return new MailInspectionJobSnapshot(
                publicId,
                type,
                status.get(),
                requestedCount,
                processedCount.get(),
                runningCount.get(),
                queuedCount.get(),
                recoveredAfterRestart,
                resumeRequired,
                resultHistoryLost,
                lostResultCount,
                remaining,
                remaining,
                businessConcurrency,
                dispatchFailedCount.get(),
                submissionChunkCount,
                confirmedSubmissionChunkCount(),
                dispatchedSubmissionChunkCount(),
                Math.max(0, submissionChunkCount
                        - confirmedSubmissionChunkCount()),
                submissionExpiresAt,
                recoveredAt,
                pendingItems,
                createdAt,
                startedAt,
                completedAt,
                expiresAt,
                new MailInspectionJobSummary(Map.copyOf(counts)),
                ordered);
    }
}
