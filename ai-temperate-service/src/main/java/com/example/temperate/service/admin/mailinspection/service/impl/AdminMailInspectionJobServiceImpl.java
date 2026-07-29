package com.example.temperate.service.admin.mailinspection.service.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
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
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 编排管理员邮箱检查的幂等创建、查询与人工恢复，并以类型生命周期锁隔离恢复和清理竞态。
 *
 * <p>本实现只在全部 Submission Chunk 获得 Publisher Confirm 后返回任务；逐凭证 Work 消息由独立 Dispatcher 异步生成。</p>
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
    private final SnowflakeIdWorker snowflakeIdWorker;
    private final PublicIdCodec publicIdCodec;
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
            SnowflakeIdWorker snowflakeIdWorker,
            PublicIdCodec publicIdCodec,
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
        this.snowflakeIdWorker = Objects.requireNonNull(snowflakeIdWorker);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
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
        MailboxCredentialParseBatch batch = parser.parse(
                command.credentialLines());
        MailInspectionRequestFingerprint fingerprint =
                fingerprinter.fingerprint(type, command);

        long candidateId = snowflakeIdWorker.nextId();
        String candidatePublicId = publicIdCodec.encode(candidateId);
        Instant candidateCreatedAt = clock.instant();
        List<MailInspectionSubmissionChunkMessage> candidateChunks =
                chunks(
                        command.clientRequestId(),
                        fingerprint,
                        candidateId,
                        candidatePublicId,
                        type,
                        command.businessConcurrency(),
                        candidateCreatedAt,
                        batch);
        MailInspectionJobState candidate = MailInspectionJobState.submitting(
                candidateId,
                candidatePublicId,
                type,
                batch.requestedCount(),
                batch.credentials().size(),
                batch.duplicateCount(),
                batch.invalidCount(),
                command.businessConcurrency(),
                command.clientRequestId(),
                fingerprint,
                candidateChunks.size(),
                candidateCreatedAt,
                properties.submission().incompleteRetention(),
                batch.immediateResults());

        MailInspectionJobReservation reservation = jobStore.reserveOrFind(
                command.clientRequestId(),
                fingerprint,
                candidate);
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
        MailInspectionJobState state = reservation.state();
        if (replayed && !requiresSubmission(state.status())) {
            return Mono.just(createResult(state, true));
        }

        List<MailInspectionSubmissionChunkMessage> messages = replayed
                ? chunks(
                        state.clientRequestId(),
                        state.requestFingerprint(),
                        state.internalId(),
                        state.publicId(),
                        state.type(),
                        state.businessConcurrency(),
                        state.createdAt(),
                        batch)
                : candidateChunks;
        if (!state.markDispatching(
                clock.instant(),
                properties.submission().incompleteRetention())) {
            return Mono.error(submissionIncomplete(
                    new IllegalStateException(
                            "mail inspection submission cleanup already claimed")));
        }
        return publishMissingChunks(state, messages)
                // Chunk Confirm 回调在订阅后才执行，完整性判断也必须延迟到发布链完成之后。
                .then(Mono.defer(() -> startSubmissionIfRequired(state)))
                .then(Mono.fromSupplier(() -> createResult(state, replayed)))
                .onErrorMap(exception -> {
                    state.markAwaitingClientResubmission(
                            clock.instant(),
                            properties.submission().incompleteRetention());
                    return submissionIncomplete(exception);
                });
    }

    @Override
    public MailInspectionJobSnapshot get(long internalJobId) {
        return requiredState(internalJobId).snapshot();
    }

    @Override
    public List<MailInspectionJobSnapshot> getRecovered() {
        return jobStore.findRecovered();
    }

    @Override
    public Mono<MailInspectionJobSnapshot> resume(long internalJobId) {
        MailInspectionJobState state = requiredState(internalJobId);
        return Mono.defer(() -> lifecycleGuard.withLock(
                state.type(),
                () -> resumeWithinLifecycle(state)));
    }

    private Mono<MailInspectionJobSnapshot> resumeWithinLifecycle(
            MailInspectionJobState state) {
        if (!state.isAwaitingResume()) {
            return Mono.error(conflict());
        }
        // 有 Submission 尚未派发时，管理员批准先恢复 Dispatcher，Work 消费者仍保持停止。
        if (state.submissionChunkCount() > 0
                && !state.allSubmissionChunksDispatched()) {
            if (!state.markDispatching(
                    clock.instant(),
                    properties.submission().incompleteRetention())) {
                return Mono.error(conflict());
            }
            return submissionListenerControl.start(state.type())
                    .then(Mono.fromSupplier(state::snapshot))
                    .onErrorMap(exception -> {
                        state.markAwaitingAdminResume();
                        return unavailable(exception);
                    });
        }
        if (!state.markRunning(clock.instant())) {
            return Mono.error(conflict());
        }
        return workListenerControl
                .prepare(state.type(), state.businessConcurrency())
                .then(workListenerControl.start(
                        state.type(), state.businessConcurrency()))
                .then(Mono.fromSupplier(state::snapshot))
                .onErrorMap(exception -> {
                    state.markAwaitingAdminResume();
                    return unavailable(exception);
                });
    }

    private List<MailInspectionSubmissionChunkMessage> chunks(
            String clientRequestId,
            MailInspectionRequestFingerprint fingerprint,
            long internalId,
            String publicId,
            MailInspectionType type,
            int businessConcurrency,
            Instant createdAt,
            MailboxCredentialParseBatch batch) {
        return submissionMessageFactory.createChunks(
                clientRequestId,
                fingerprint,
                internalId,
                publicId,
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
     * HTTP 取消只会停止尚未开始的发布；已经确认的索引保存在任务状态中，相同幂等键重试只补发缺口。
     */
    private Mono<Void> publishMissingChunks(
            MailInspectionJobState state,
            List<MailInspectionSubmissionChunkMessage> chunks) {
        return Flux.fromIterable(chunks)
                .filter(message -> !state.isSubmissionChunkConfirmed(
                        message.chunkIndex()))
                .flatMap(message -> submissionPublisher.publish(message)
                                .then(Mono.fromRunnable(() ->
                                        state.confirmSubmissionChunk(
                                                message.chunkIndex(),
                                                clock.instant(),
                                                properties.submission()
                                                        .incompleteRetention()))),
                        properties.submission().publishConcurrency())
                .then();
    }

    private Mono<Void> startSubmissionIfRequired(
            MailInspectionJobState state) {
        if (state.hasCompletedWork()) {
            state.complete(clock.instant(), properties.job().retention());
            return Mono.empty();
        }
        if (!state.allSubmissionChunksConfirmed()) {
            return Mono.error(new IllegalStateException(
                    "mail inspection submission remains incomplete"));
        }
        if (state.recoveredAfterRestart()) {
            state.markAwaitingAdminResume();
            return Mono.empty();
        }
        return submissionListenerControl.start(state.type())
                .onErrorResume(exception -> {
                    state.markAwaitingAdminResume();
                    return Mono.empty();
                });
    }

    private MailInspectionJobCreateResult createResult(
            MailInspectionJobState state,
            boolean replayed) {
        MailInspectionJobSnapshot snapshot = state.snapshot();
        return new MailInspectionJobCreateResult(
                snapshot.jobId(),
                snapshot.inspectionType(),
                snapshot.status(),
                snapshot.requestedCount(),
                state.acceptedCount(),
                state.duplicateCount(),
                state.invalidCount(),
                state.businessConcurrency(),
                state.businessConcurrency(),
                snapshot.dispatchFailedCount(),
                replayed,
                snapshot.submissionChunkCount(),
                snapshot.confirmedSubmissionChunkCount(),
                snapshot.dispatchedSubmissionChunkCount(),
                snapshot.submissionPendingChunkCount(),
                snapshot.submissionExpiresAt(),
                snapshot.createdAt(),
                properties.job().pollAfter().toMillis());
    }

    private void validateBusinessConcurrency(int value) {
        if (value < 1
                || value > properties.job().maxBusinessConcurrency()) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                    "mail inspection business concurrency is invalid");
        }
    }

    private MailInspectionJobState requiredState(long internalJobId) {
        return jobStore.find(internalJobId)
                .orElseThrow(() -> new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                        "mail inspection job not found"));
    }

    private static boolean requiresSubmission(MailInspectionJobStatus status) {
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
     * 关闭时同时停止 Submission 和 Work 监听器；Rabbit 会把尚未 ACK 的消息恢复为 Ready。
     */
    @PreDestroy
    public void shutdown() {
        jobStore.stopAllAccepting();
        submissionListenerControl.stopAll();
        workListenerControl.stopAll();
    }
}
