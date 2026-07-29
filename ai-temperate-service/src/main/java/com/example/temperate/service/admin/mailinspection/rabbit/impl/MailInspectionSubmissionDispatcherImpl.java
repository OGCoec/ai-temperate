package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPoisonMessageException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionCredential;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionDispatcher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionMessageFactory;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkPublisher;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 实现提交分块到工作消息的可靠派发顺序，并以持久 Marker 作为分块已派发的恢复事实。
 *
 * <p>工作消息全部确认、Marker 再确认后才允许 Submission Listener 返回成功；任何毒消息均直接进入独立死信边界。</p>
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
    private final PublicIdCodec publicIdCodec;
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
            PublicIdCodec publicIdCodec,
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
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Mono<Void> dispatch(
            MailInspectionType expectedType,
            MailInspectionSubmissionChunkMessage message) {
        return Mono.defer(() -> {
                    validateEnvelope(expectedType, message);
                    MailInspectionJobState state = jobStore
                            .find(message.jobInternalId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "mail inspection submission state unavailable"));
                    validateState(state, message);
                    if (state.isSubmissionChunkDispatched(
                            message.chunkIndex())) {
                        return Mono.empty();
                    }
                    List<MailInspectionSubmissionCredential> credentials =
                            protector.unprotect(message);
                    return publishWork(state, message, credentials)
                            // Marker 的发布调用本身也必须延迟，保证全部 Work Confirm 已经完成。
                            .then(Mono.defer(() -> markerPublisher.publish(
                                    submissionMessageFactory.createMarker(message))))
                            .then(finishChunk(state, message));
                })
                .onErrorMap(
                        this::isPoison,
                        exception -> new AmqpRejectAndDontRequeueException(
                                "mail inspection submission poison message rejected",
                                exception));
    }

    private Mono<Void> publishWork(
            MailInspectionJobState state,
            MailInspectionSubmissionChunkMessage message,
            List<MailInspectionSubmissionCredential> credentials) {
        return Flux.fromIterable(credentials)
                .flatMap(credential -> workPublisher
                                .publish(workMessageFactory
                                        .createFromSubmission(message, credential))
                                .onErrorResume(ignored -> {
                                    state.recordDispatchFailure(
                                            dispatchFailure(credential));
                                    return Mono.empty();
                                }),
                        properties.submission().workDispatchConcurrency())
                .then();
    }

    /**
     * Marker 已经持久确认后才更新内存派发事实；最后一个分块负责一次性启动工作消费者。
     */
    private Mono<Void> finishChunk(
            MailInspectionJobState state,
            MailInspectionSubmissionChunkMessage message) {
        return Mono.defer(() -> {
            state.markSubmissionChunkDispatched(message.chunkIndex());
            if (!state.allSubmissionChunksDispatched()) {
                return Mono.empty();
            }
            state.markQueued();
            if (state.hasCompletedWork()) {
                state.complete(clock.instant(), properties.job().retention());
                return Mono.empty();
            }
            if (!state.markRunning(clock.instant())) {
                return Mono.error(new IllegalStateException(
                        "mail inspection job cannot enter running state"));
            }
            return workListenerControl
                    .prepare(state.type(), state.businessConcurrency())
                    // start 只能在 prepare 完成后调用，避免实现类在方法入口产生提前副作用。
                    .then(Mono.defer(() -> workListenerControl.start(
                            state.type(), state.businessConcurrency())))
                    .onErrorResume(exception -> {
                        state.markAwaitingAdminResume();
                        return Mono.empty();
                    });
        });
    }

    private void validateEnvelope(
            MailInspectionType expectedType,
            MailInspectionSubmissionChunkMessage message) {
        if (message == null
                || message.schemaVersion()
                        != MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION
                || !MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE.equals(
                        message.eventType())
                || message.inspectionType() != expectedType
                || message.jobInternalId() <= 0
                || !publicIdCodec.encode(message.jobInternalId())
                        .equals(message.jobId())
                || message.chunkIndex() < 0
                || message.chunkIndex() >= message.chunkCount()) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection submission envelope is invalid");
        }
    }

    private static void validateState(
            MailInspectionJobState state,
            MailInspectionSubmissionChunkMessage message) {
        if (!state.publicId().equals(message.jobId())
                || state.type() != message.inspectionType()
                || state.businessConcurrency() != message.businessConcurrency()
                || !Objects.equals(state.clientRequestId(),
                        message.clientRequestId())
                || state.requestFingerprint() == null
                || !state.requestFingerprint().value().equals(
                        message.requestFingerprint())) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection submission does not match job state");
        }
        MailInspectionJobStatus status = state.status();
        if (status != MailInspectionJobStatus.DISPATCHING
                && status != MailInspectionJobStatus.AWAITING_ADMIN_RESUME) {
            throw new IllegalStateException(
                    "mail inspection submission dispatcher is not approved");
        }
    }

    private boolean isPoison(Throwable exception) {
        return exception instanceof com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPayloadException
                || exception instanceof MailInspectionPoisonMessageException;
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
