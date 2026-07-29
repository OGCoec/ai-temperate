package com.example.temperate.service.admin.mailinspection.service.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobCreateResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservation;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservationStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.example.temperate.service.admin.mailinspection.parser.MailboxCredentialParseBatch;
import com.example.temperate.service.admin.mailinspection.parser.MailboxCredentialParser;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionPublisher;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.example.temperate.service.admin.mailinspection.security.MailInspectionRequestFingerprinter;
import com.example.temperate.service.admin.mailinspection.service.AdminMailInspectionJobService;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategyRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 编排 Redis 权威邮箱任务的幂等创建、持久提交、查询和管理员恢复。
 *
 * <p>本实现只持有不可变文档；每次状态变化都回到 JobStore 的 Lua 原子命令，Rabbit Confirm 完成后才登记提交分块。</p>
 */
@Service
public final class AdminMailInspectionJobServiceImpl
        implements AdminMailInspectionJobService {

    private final MailboxCredentialParser parser;
    private final MailInspectionStrategyRegistry strategyRegistry;
    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionRequestFingerprinter fingerprinter;
    private final MailInspectionSubmissionMessageFactory submissionMessageFactory;
    private final MailInspectionSubmissionPublisher submissionPublisher;
    private final MailInspectionSubmissionListenerControl submissionListenerControl;
    private final MailInspectionListenerControl workListenerControl;
    private final HybridSemaphoreIdWorker hybridIdWorker;
    private final HybridBase64UrlCodec jobIdCodec;
    private final MailInspectionJobKeyHasher keyHasher;
    private final AdminMailInspectionProperties properties;
    private final Clock clock;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;

    public AdminMailInspectionJobServiceImpl(
            MailboxCredentialParser parser,
            MailInspectionStrategyRegistry strategyRegistry,
            AdminMailInspectionJobStore jobStore,
            MailInspectionRequestFingerprinter fingerprinter,
            MailInspectionSubmissionMessageFactory submissionMessageFactory,
            MailInspectionSubmissionPublisher submissionPublisher,
            MailInspectionSubmissionListenerControl submissionListenerControl,
            MailInspectionListenerControl workListenerControl,
            HybridSemaphoreIdWorker hybridIdWorker,
            HybridBase64UrlCodec jobIdCodec,
            MailInspectionJobKeyHasher keyHasher,
            AdminMailInspectionProperties properties,
            Clock clock,
            MailInspectionTypeLifecycleGuard lifecycleGuard) {
        this.parser = Objects.requireNonNull(parser);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
        this.jobStore = Objects.requireNonNull(jobStore);
        this.fingerprinter = Objects.requireNonNull(fingerprinter);
        this.submissionMessageFactory = Objects.requireNonNull(
                submissionMessageFactory);
        this.submissionPublisher = Objects.requireNonNull(submissionPublisher);
        this.submissionListenerControl = Objects.requireNonNull(
                submissionListenerControl);
        this.workListenerControl = Objects.requireNonNull(workListenerControl);
        this.hybridIdWorker = Objects.requireNonNull(hybridIdWorker);
        this.jobIdCodec = Objects.requireNonNull(jobIdCodec);
        this.keyHasher = Objects.requireNonNull(keyHasher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
    }

    @Override
    public Mono<MailInspectionJobCreateResult> create(
            MailInspectionType type,
            AdminMailInspectionCreateCommand command) {
        return Mono.defer(() -> lifecycleGuard.withLock(
                type,
                () -> createWithinLifecycle(type, command)));
    }

    private Mono<MailInspectionJobCreateResult> createWithinLifecycle(
            MailInspectionType type,
            AdminMailInspectionCreateCommand command) {
        strategyRegistry.getRequired(type);
        validateBusinessConcurrency(command.businessConcurrency());
        if (command.credentialLines().size()
                > properties.job().maxCredentialLines()) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                    "mail inspection credential line limit exceeded");
        }
        MailboxCredentialParseBatch batch = parser.parse(
                command.credentialLines());
        MailInspectionRequestFingerprint fingerprint =
                fingerprinter.fingerprint(type, command);
        String candidateJobId = jobIdCodec.encode(hybridIdWorker.nextId());
        String candidateJobHash =
                keyHasher.hashJobId(candidateJobId).value();
        Instant createdAt = clock.instant();
        List<MailInspectionSubmissionChunkMessage> candidateChunks = chunks(
                command.clientRequestId(),
                fingerprint,
                candidateJobId,
                candidateJobHash,
                type,
                command.businessConcurrency(),
                createdAt,
                batch);
        MailInspectionRedisJobDocument candidate = new MailInspectionRedisJobDocument(
                MailInspectionRedisJobDocument.SCHEMA_VERSION,
                candidateJobId,
                candidateJobHash,
                type,
                MailInspectionJobStatus.DISPATCHING,
                batch.requestedCount(),
                batch.credentials().size(),
                batch.duplicateCount(),
                batch.invalidCount(),
                command.businessConcurrency(),
                batch.requestedCount(),
                command.clientRequestId(),
                fingerprint.value(),
                candidateChunks.size(),
                false,
                false,
                0,
                false,
                List.of(),
                createdAt,
                null,
                null,
                createdAt.plus(properties.job().activeLease()),
                createdAt.plus(
                        properties.submission().incompleteRetention()),
                null,
                0L);

        MailInspectionJobReservation reservation = jobStore.reserveOrFind(
                candidate, batch.immediateResults());
        if (reservation.status()
                == MailInspectionJobReservationStatus.FINGERPRINT_CONFLICT) {
            return Mono.error(idempotencyConflict());
        }
        if (reservation.status()
                == MailInspectionJobReservationStatus.TYPE_CAPACITY_CONFLICT) {
            return Mono.error(conflict());
        }

        boolean replayed = reservation.status()
                == MailInspectionJobReservationStatus.REPLAYED;
        MailInspectionRedisJobDocument document = reservation.document();
        if (replayed && !requiresSubmission(document.status())) {
            return Mono.just(createResult(
                    requiredSnapshot(document.jobId()), true));
        }
        List<MailInspectionSubmissionChunkMessage> messages = replayed
                ? chunks(
                        document.clientRequestId(),
                        new MailInspectionRequestFingerprint(
                                document.requestFingerprint()),
                        document.jobId(),
                        document.jobHash(),
                        document.inspectionType(),
                        document.businessConcurrency(),
                        document.createdAt(),
                        batch)
                : candidateChunks;
        if (replayed) {
            jobStore.changeStatus(
                    document.jobId(),
                    Set.of(
                            MailInspectionJobStatus.DISPATCHING,
                            MailInspectionJobStatus
                                    .AWAITING_CLIENT_RESUBMISSION),
                    MailInspectionJobStatus.DISPATCHING,
                    clock.instant());
        }
        return publishMissingChunks(document.jobId(), messages)
                .then(Mono.defer(() ->
                        startSubmissionIfRequired(document.jobId())))
                .then(Mono.fromSupplier(() -> createResult(
                        requiredSnapshot(document.jobId()), replayed)))
                .onErrorMap(exception -> {
                    jobStore.changeStatus(
                            document.jobId(),
                            Set.of(MailInspectionJobStatus.DISPATCHING),
                            MailInspectionJobStatus
                                    .AWAITING_CLIENT_RESUBMISSION,
                            clock.instant());
                    return submissionIncomplete(exception);
                });
    }

    @Override
    public MailInspectionJobSnapshot get(String jobId) {
        return requiredSnapshot(jobId);
    }

    @Override
    public List<MailInspectionJobSnapshot> getRecovered() {
        return jobStore.findRecovered();
    }

    @Override
    public Mono<MailInspectionJobSnapshot> resume(String jobId) {
        MailInspectionRedisJobDocument document = requiredDocument(jobId);
        return Mono.defer(() -> lifecycleGuard.withLock(
                document.inspectionType(),
                () -> resumeWithinLifecycle(document)));
    }

    private Mono<MailInspectionJobSnapshot> resumeWithinLifecycle(
            MailInspectionRedisJobDocument document) {
        if (document.status()
                != MailInspectionJobStatus.AWAITING_ADMIN_RESUME) {
            return Mono.error(conflict());
        }
        MailInspectionJobSnapshot snapshot =
                requiredSnapshot(document.jobId());
        if (snapshot.submissionChunkCount() > 0
                && snapshot.dispatchedSubmissionChunkCount()
                < snapshot.submissionChunkCount()) {
            if (!jobStore.changeStatus(
                    document.jobId(),
                    Set.of(MailInspectionJobStatus.AWAITING_ADMIN_RESUME),
                    MailInspectionJobStatus.DISPATCHING,
                    clock.instant())) {
                return Mono.error(conflict());
            }
            return submissionListenerControl
                    .start(document.inspectionType())
                    .then(Mono.fromSupplier(() ->
                            requiredSnapshot(document.jobId())))
                    .onErrorMap(exception -> {
                        returnToAwaitingResume(document.jobId());
                        return unavailable(exception);
                    });
        }
        if (!jobStore.changeStatus(
                document.jobId(),
                Set.of(MailInspectionJobStatus.AWAITING_ADMIN_RESUME),
                MailInspectionJobStatus.RUNNING,
                clock.instant())) {
            return Mono.error(conflict());
        }
        return workListenerControl
                .prepare(
                        document.inspectionType(),
                        document.businessConcurrency())
                .then(workListenerControl.start(
                        document.inspectionType(),
                        document.businessConcurrency()))
                .then(Mono.fromSupplier(() ->
                        requiredSnapshot(document.jobId())))
                .onErrorMap(exception -> {
                    returnToAwaitingResume(document.jobId());
                    return unavailable(exception);
                });
    }

    private List<MailInspectionSubmissionChunkMessage> chunks(
            String clientRequestId,
            MailInspectionRequestFingerprint fingerprint,
            String jobId,
            String jobKeyHash,
            MailInspectionType type,
            int businessConcurrency,
            Instant createdAt,
            MailboxCredentialParseBatch batch) {
        return submissionMessageFactory.createChunks(
                clientRequestId,
                fingerprint,
                jobId,
                jobKeyHash,
                type,
                batch.requestedCount(),
                batch.credentials().size(),
                batch.duplicateCount(),
                batch.invalidCount(),
                businessConcurrency,
                createdAt,
                batch.credentials());
    }

    /**
     * HTTP 取消只停止尚未开始的发布；Redis 中的确认位使相同幂等请求只补发缺失分块。
     */
    private Mono<Void> publishMissingChunks(
            String jobId,
            List<MailInspectionSubmissionChunkMessage> chunks) {
        Set<Integer> confirmed =
                jobStore.confirmedSubmissionChunks(jobId);
        return Flux.fromIterable(chunks)
                .filter(message -> !confirmed.contains(message.chunkIndex()))
                .flatMap(message -> submissionPublisher.publish(message)
                                .then(Mono.fromRunnable(() ->
                                        jobStore.recordSubmissionConfirmed(
                                                jobId,
                                                message.chunkIndex(),
                                                clock.instant()))),
                        properties.submission().publishConcurrency())
                .then();
    }

    private Mono<Void> startSubmissionIfRequired(String jobId) {
        MailInspectionJobSnapshot snapshot = requiredSnapshot(jobId);
        if (snapshot.processedCount() >= snapshot.requestedCount()) {
            jobStore.markTerminal(
                    jobId,
                    MailInspectionJobStatus.COMPLETED,
                    clock.instant());
            return Mono.empty();
        }
        if (snapshot.confirmedSubmissionChunkCount()
                < snapshot.submissionChunkCount()) {
            return Mono.error(new IllegalStateException(
                    "mail inspection submission remains incomplete"));
        }
        MailInspectionRedisJobDocument document = requiredDocument(jobId);
        if (document.recoveredAfterRestart()) {
            returnToAwaitingResume(jobId);
            return Mono.empty();
        }
        return submissionListenerControl.start(document.inspectionType())
                .onErrorResume(exception -> {
                    returnToAwaitingResume(jobId);
                    return Mono.empty();
                });
    }

    private void returnToAwaitingResume(String jobId) {
        jobStore.changeStatus(
                jobId,
                Set.of(
                        MailInspectionJobStatus.DISPATCHING,
                        MailInspectionJobStatus.QUEUED,
                        MailInspectionJobStatus.RUNNING,
                        MailInspectionJobStatus.RECOVERY_FAILED),
                MailInspectionJobStatus.AWAITING_ADMIN_RESUME,
                clock.instant());
    }

    private MailInspectionJobCreateResult createResult(
            MailInspectionJobSnapshot snapshot,
            boolean replayed) {
        return new MailInspectionJobCreateResult(
                snapshot.jobId(),
                snapshot.inspectionType(),
                snapshot.status(),
                snapshot.requestedCount(),
                snapshot.acceptedCount(),
                snapshot.duplicateCount(),
                snapshot.invalidCount(),
                snapshot.businessConcurrency(),
                snapshot.businessConcurrency(),
                snapshot.dispatchFailedCount(),
                replayed,
                snapshot.submissionChunkCount(),
                snapshot.confirmedSubmissionChunkCount(),
                snapshot.dispatchedSubmissionChunkCount(),
                snapshot.submissionPendingChunkCount(),
                snapshot.submissionExpiresAt(),
                snapshot.createdAt());
    }

    private void validateBusinessConcurrency(int value) {
        if (value < 1
                || value > properties.job().maxBusinessConcurrency()) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                    "mail inspection business concurrency is invalid");
        }
    }

    private MailInspectionRedisJobDocument requiredDocument(String jobId) {
        return jobStore.findSnapshotMeta(jobId)
                .orElseThrow(() -> new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                        "mail inspection job not found"));
    }

    private MailInspectionJobSnapshot requiredSnapshot(String jobId) {
        return jobStore.findSnapshot(jobId)
                .orElseThrow(() -> new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                        "mail inspection job not found"));
    }

    private static boolean requiresSubmission(
            MailInspectionJobStatus status) {
        return status == MailInspectionJobStatus.DISPATCHING
                || status
                == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION;
    }

    private static AdminException conflict() {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_CONFLICT,
                "mail inspection job conflicts with an active task");
    }

    private static AdminException idempotencyConflict() {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_IDEMPOTENCY_CONFLICT,
                "mail inspection idempotency request changed");
    }

    private static AdminException submissionIncomplete(Throwable cause) {
        if (cause instanceof AdminException adminException) {
            return adminException;
        }
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_SUBMISSION_INCOMPLETE,
                "mail inspection submission is incomplete",
                cause);
    }

    private static AdminException unavailable(Throwable cause) {
        if (cause instanceof AdminException adminException) {
            return adminException;
        }
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                "mail inspection Rabbit consumer is unavailable",
                cause);
    }

    /**
     * 关闭时先关闭 Redis 接收闸门，再停止 Submission 与 Work 监听器，使未 ACK 消息返回 Rabbit Ready。
     */
    @PreDestroy
    public void shutdown() {
        // 关闭阶段按控制面逐项尽力执行；Redis 故障不能阻断 Rabbit 监听器停止，反之亦然。
        try {
            jobStore.stopAllAccepting();
        } catch (RuntimeException ignored) {
            // Redis 已不可用时无法持久化闸门，但进程退出仍必须继续停止全部监听器。
        }
        try {
            submissionListenerControl.stopAll();
        } catch (RuntimeException ignored) {
            // Submission 控制面异常不能阻断 Work 控制面关闭。
        }
        try {
            workListenerControl.stopAll();
        } catch (RuntimeException ignored) {
            // 进程关闭阶段不以控制面清理异常覆盖原始退出流程。
        }
    }
}
