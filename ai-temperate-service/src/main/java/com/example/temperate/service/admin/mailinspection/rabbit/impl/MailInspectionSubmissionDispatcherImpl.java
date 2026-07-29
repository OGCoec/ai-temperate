package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobSnapshot;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPayloadException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPoisonMessageException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionCredential;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionDispatcher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkPublisher;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 将持久 Submission Chunk 派发为工作消息，并在全部 Work 与 Marker Confirm 后原子更新 Redis。
 *
 * <p>Redis 是唯一派发事实；Redis 写失败会令监听 Mono 失败并阻止 ACK，不会回退到 JVM 内存。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionSubmissionDispatcherImpl
        implements MailInspectionSubmissionDispatcher {

    private final AdminMailInspectionJobStore jobStore;
    private final AdminMailInspectionSubmissionPayloadProtector protector;
    private final MailInspectionRabbitMessageFactory workMessageFactory;
    private final MailInspectionSubmissionMessageFactory submissionMessageFactory;
    private final MailInspectionWorkPublisher workPublisher;
    private final MailInspectionDispatchMarkerPublisher markerPublisher;
    private final MailInspectionListenerControl workListenerControl;
    private final MailInspectionSubmissionListenerControl
            submissionListenerControl;
    private final HybridBase64UrlCodec jobIdCodec;
    private final MailInspectionJobKeyHasher keyHasher;
    private final AdminMailInspectionProperties properties;
    private final Clock clock;

    public MailInspectionSubmissionDispatcherImpl(
            AdminMailInspectionJobStore jobStore,
            AdminMailInspectionSubmissionPayloadProtector protector,
            MailInspectionRabbitMessageFactory workMessageFactory,
            MailInspectionSubmissionMessageFactory submissionMessageFactory,
            MailInspectionWorkPublisher workPublisher,
            MailInspectionDispatchMarkerPublisher markerPublisher,
            MailInspectionListenerControl workListenerControl,
            MailInspectionSubmissionListenerControl submissionListenerControl,
            HybridBase64UrlCodec jobIdCodec,
            MailInspectionJobKeyHasher keyHasher,
            AdminMailInspectionProperties properties,
            Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.protector = Objects.requireNonNull(protector);
        this.workMessageFactory = Objects.requireNonNull(workMessageFactory);
        this.submissionMessageFactory = Objects.requireNonNull(
                submissionMessageFactory);
        this.workPublisher = Objects.requireNonNull(workPublisher);
        this.markerPublisher = Objects.requireNonNull(markerPublisher);
        this.workListenerControl = Objects.requireNonNull(workListenerControl);
        this.submissionListenerControl = Objects.requireNonNull(
                submissionListenerControl);
        this.jobIdCodec = Objects.requireNonNull(jobIdCodec);
        this.keyHasher = Objects.requireNonNull(keyHasher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Mono<Void> dispatch(
            MailInspectionType expectedType,
            MailInspectionSubmissionChunkMessage message) {
        return Mono.defer(() -> {
                    validateEnvelope(expectedType, message);
                    MailInspectionRedisJobDocument document =
                            jobStore.findSnapshotMeta(message.jobId())
                                    .orElseThrow(() ->
                                            new AdminException(
                                                    AdminErrorCode
                                                            .ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                                                    "mail inspection Redis job unavailable"));
                    validateDocument(document, message);
                    if (jobStore.isSubmissionChunkDispatched(
                            message.jobId(), message.chunkIndex())) {
                        return Mono.empty();
                    }
                    List<MailInspectionSubmissionCredential> credentials =
                            protector.unprotect(message);
                    return publishWork(message, credentials)
                            // Marker 必须在全部 Work Confirm 之后发布；Redis 派发位又必须在 Marker Confirm 之后写入。
                            .then(Mono.defer(() -> markerPublisher.publish(
                                    submissionMessageFactory.createMarker(
                                            message))))
                            .then(finishChunk(document, message));
                })
                .doOnError(exception ->
                        pauseOnRedisFailure(expectedType, exception))
                .onErrorMap(
                        this::isPoison,
                        exception -> new AmqpRejectAndDontRequeueException(
                                "mail inspection submission poison message rejected",
                                exception));
    }

    private Mono<Void> publishWork(
            MailInspectionSubmissionChunkMessage message,
            List<MailInspectionSubmissionCredential> credentials) {
        return Flux.fromIterable(credentials)
                .flatMap(credential -> workPublisher
                                .publish(workMessageFactory
                                        .createFromSubmission(
                                                message, credential))
                                .onErrorResume(ignored ->
                                        Mono.fromRunnable(() ->
                                                jobStore.recordResult(
                                                        message.jobId(),
                                                        dispatchFailure(
                                                                credential),
                                                        clock.instant()))),
                        properties.submission().workDispatchConcurrency())
                .then();
    }

    private Mono<Void> finishChunk(
            MailInspectionRedisJobDocument document,
            MailInspectionSubmissionChunkMessage message) {
        return Mono.defer(() -> {
            jobStore.recordSubmissionDispatched(
                    message.jobId(), message.chunkIndex(), clock.instant());
            MailInspectionJobSnapshot snapshot = jobStore
                    .findSnapshot(message.jobId())
                    .orElseThrow(() -> new IllegalStateException(
                            "mail inspection Redis snapshot unavailable"));
            if (snapshot.dispatchedSubmissionChunkCount()
                    < snapshot.submissionChunkCount()) {
                return Mono.empty();
            }
            if (snapshot.processedCount() >= snapshot.requestedCount()) {
                jobStore.markTerminal(
                        message.jobId(),
                        MailInspectionJobStatus.COMPLETED,
                        clock.instant());
                return Mono.empty();
            }
            jobStore.changeStatus(
                    message.jobId(),
                    Set.of(
                            MailInspectionJobStatus.DISPATCHING,
                            MailInspectionJobStatus.QUEUED),
                    MailInspectionJobStatus.RUNNING,
                    clock.instant());
            return workListenerControl
                    .prepare(
                            document.inspectionType(),
                            document.businessConcurrency())
                    .then(Mono.defer(() -> workListenerControl.start(
                            document.inspectionType(),
                            document.businessConcurrency())))
                    .onErrorResume(exception -> {
                        jobStore.changeStatus(
                                message.jobId(),
                                Set.of(MailInspectionJobStatus.RUNNING),
                                MailInspectionJobStatus.AWAITING_ADMIN_RESUME,
                                clock.instant());
                        return Mono.empty();
                    });
        });
    }

    private void validateEnvelope(
            MailInspectionType expectedType,
            MailInspectionSubmissionChunkMessage message) {
        try {
            if (message == null
                    || message.schemaVersion()
                    != MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION
                    || !MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE.equals(
                            message.eventType())
                    || message.inspectionType() != expectedType
                    || jobIdCodec.decode(message.jobId()).length
                    != HybridBase64UrlCodec.BINARY_LENGTH
                    || !keyHasher.hashJobId(message.jobId()).value()
                            .equals(message.jobKeyHash())
                    || message.chunkIndex() < 0
                    || message.chunkIndex() >= message.chunkCount()) {
                throw new MailInspectionPoisonMessageException(
                        "mail inspection submission envelope is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection submission envelope is invalid",
                    exception);
        }
    }

    private static void validateDocument(
            MailInspectionRedisJobDocument document,
            MailInspectionSubmissionChunkMessage message) {
        // Redis 文档是唯一事实来源，分块消息不得通过篡改计数、并发或幂等身份扩大任务处理边界。
        if (!document.jobId().equals(message.jobId())
                || !document.jobHash().equals(message.jobKeyHash())
                || document.inspectionType() != message.inspectionType()
                || document.requestedCount() != message.requestedCount()
                || document.acceptedCount() != message.acceptedCount()
                || document.duplicateCount() != message.duplicateCount()
                || document.invalidCount() != message.invalidCount()
                || document.businessConcurrency()
                != message.businessConcurrency()
                || !Objects.equals(
                        document.clientRequestId(),
                        message.clientRequestId())
                || !Objects.equals(
                        document.requestFingerprint(),
                        message.requestFingerprint())
                || document.submissionChunkCount() != message.chunkCount()) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection submission does not match Redis job");
        }
        if (document.status() != MailInspectionJobStatus.DISPATCHING
                && document.status()
                != MailInspectionJobStatus.AWAITING_ADMIN_RESUME) {
            throw new IllegalStateException(
                    "mail inspection submission dispatcher is not approved");
        }
    }

    private boolean isPoison(Throwable exception) {
        return exception instanceof MailInspectionPayloadException
                || exception instanceof MailInspectionPoisonMessageException;
    }

    private void pauseOnRedisFailure(
            MailInspectionType type, Throwable failure) {
        if (!isRedisAuthorityFailure(failure)) {
            return;
        }
        // Submission 与 Work 都依赖同一 Redis 权威文档，异步停止可避免消费线程等待自身 ACK 通道退出。
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            try {
                submissionListenerControl.stop(type);
            } catch (RuntimeException ignored) {
                // 一个控制面停止失败不能阻断另一个控制面关闭。
            }
            try {
                workListenerControl.stop(type);
            } catch (RuntimeException ignored) {
                // 原始 Redis 故障继续向 Rabbit 返回，恢复协调器会在重启检查时保持该类型不可用。
            }
        });
    }

    private static boolean isRedisAuthorityFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AdminException adminException
                    && (adminException.code()
                    == AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE
                    || adminException.code()
                    == AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MailInspectionResult dispatchFailure(
            MailInspectionSubmissionCredential credential) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                MailInspectionResultStatus.RABBIT_DISPATCH_EXHAUSTED,
                MailInspectionFailureStage.MESSAGE_QUEUE,
                "rabbit_dispatch_exhausted",
                0,
                0,
                true,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
