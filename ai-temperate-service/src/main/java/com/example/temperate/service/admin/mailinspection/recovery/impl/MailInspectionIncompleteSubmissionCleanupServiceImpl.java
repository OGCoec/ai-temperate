package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionIncompleteSubmissionCleanupService;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 使用 Rabbit 原生物理会话安全删除过期残缺任务，失败时显式归还所有未结算消息。
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

    private final MailInspectionRecoveryConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionSubmissionListenerControl submissionControl;
    private final MailInspectionListenerControl workControl;
    private final AdminMailInspectionProperties properties;
    private final Clock clock;
    private final PublicIdCodec publicIdCodec;
    private final AdminMailInspectionSubmissionPayloadProtector submissionProtector;
    private final AdminMailInspectionPayloadProtector workProtector;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;
    private final MailInspectionRecoveryObserver recoveryObserver;

    public MailInspectionIncompleteSubmissionCleanupServiceImpl(
            MailInspectionRecoveryConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AdminMailInspectionJobStore jobStore,
            MailInspectionSubmissionListenerControl submissionControl,
            MailInspectionListenerControl workControl,
            AdminMailInspectionProperties properties,
            Clock clock,
            PublicIdCodec publicIdCodec,
            AdminMailInspectionSubmissionPayloadProtector submissionProtector,
            AdminMailInspectionPayloadProtector workProtector,
            MailInspectionTypeLifecycleGuard lifecycleGuard,
            MailInspectionRecoveryObserver recoveryObserver) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.jobStore = Objects.requireNonNull(jobStore);
        this.submissionControl = Objects.requireNonNull(submissionControl);
        this.workControl = Objects.requireNonNull(workControl);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.submissionProtector = Objects.requireNonNull(
                submissionProtector);
        this.workProtector = Objects.requireNonNull(workProtector);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
        this.recoveryObserver = Objects.requireNonNull(recoveryObserver);
    }

    @Override
    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.submission.cleanup-interval:PT1M}")
    public void cleanupExpiredSubmissions() {
        for (MailInspectionJobState state :
                jobStore.findIncompleteExpired(clock.instant())) {
            lifecycleGuard.withLock(
                    state.type(),
                    () -> cleanup(state));
        }
    }

    private void cleanup(MailInspectionJobState state) {
        // 原子声明确保此后相同幂等键不能一边补发新分块、一边被当前清理批次删除。
        if (!state.tryClaimIncompleteCleanup(clock.instant())) {
            return;
        }
        MailInspectionType type = state.type();
        // 停止监听器后再扫描，防止消息在验证与批量 ACK 之间被其他 Channel 取走。
        submissionControl.stop(type);
        workControl.stop(type);
        MailInspectionRecoverySession session = null;
        Set<Long> deliveryTags = new LinkedHashSet<>();
        try {
            session = connectionFactory.open(type, "incomplete-cleanup");
            Channel channel = session.channel();
            scan(channel, MailInspectionRabbitNames.submissionQueue(type),
                    MailInspectionSubmissionChunkMessage.class,
                    state, deliveryTags);
            scan(channel, MailInspectionRabbitNames.dispatchStateQueue(type),
                    MailInspectionDispatchMarkerMessage.class,
                    state, deliveryTags);
            scan(channel, MailInspectionRabbitNames.queue(type),
                    MailInspectionWorkMessage.class,
                    state, deliveryTags);
            int deletedDeliveryCount = deliveryTags.size();
            for (Long deliveryTag : List.copyOf(deliveryTags)) {
                channel.basicAck(deliveryTag, false);
                deliveryTags.remove(deliveryTag);
            }
            state.abandon(clock.instant(), properties.job().retention());
            LOGGER.info(
                    "event={} inspectionType={} deletedDeliveryCount={}",
                    "admin_mail_inspection_submission_abandoned",
                    type,
                    deletedDeliveryCount);
        } catch (Exception exception) {
            int scannedDeliveryCount = deliveryTags.size();
            requeueUnsettled(type, session, deliveryTags);
            state.markRecoveryFailed();
            LOGGER.warn(
                    "event={} inspectionType={} exceptionType={} scannedDeliveryCount={}",
                    "admin_mail_inspection_submission_cleanup_failed",
                    type,
                    exception.getClass().getName(),
                    scannedDeliveryCount);
        } finally {
            close(session);
        }
    }

    private <T> void scan(
            Channel channel,
            String queue,
            Class<T> bodyType,
            MailInspectionJobState state,
            Set<Long> deliveryTags) throws Exception {
        for (;;) {
            GetResponse response = channel.basicGet(queue, false);
            if (response == null) {
                return;
            }
            long deliveryTag = response.getEnvelope().getDeliveryTag();
            // deliveryTag 必须在反序列化前登记，坏消息也必须显式归还。
            deliveryTags.add(deliveryTag);
            T value = objectMapper.readValue(response.getBody(), bodyType);
            boolean valid = switch (value) {
                case MailInspectionSubmissionChunkMessage chunk ->
                        validateSubmission(state, chunk);
                case MailInspectionDispatchMarkerMessage marker ->
                        validateMarker(state, marker);
                case MailInspectionWorkMessage work ->
                        validateWork(state, work);
                default -> throw new IllegalStateException(
                        "unsupported mail inspection cleanup message");
            };
            if (!valid) {
                throw new IllegalStateException(
                        "mail inspection cleanup queue contains invalid identity");
            }
        }
    }

    private boolean validateSubmission(
            MailInspectionJobState state,
            MailInspectionSubmissionChunkMessage message) {
        boolean valid = message.schemaVersion()
                        == MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION
                && MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE.equals(
                        message.eventType())
                && commonIdentityMatches(
                        state,
                        message.jobInternalId(),
                        message.jobId(),
                        message.inspectionType(),
                        message.clientRequestId(),
                        message.requestFingerprint(),
                        message.businessConcurrency())
                && message.chunkCount() == state.submissionChunkCount()
                && message.requestedCount() == state.requestedCount()
                && message.acceptedCount() == state.acceptedCount()
                && message.duplicateCount() == state.duplicateCount()
                && message.invalidCount() == state.invalidCount()
                && message.chunkIndex() >= 0
                && message.chunkIndex() < message.chunkCount();
        if (valid) {
            submissionProtector.unprotect(message);
        }
        return valid;
    }

    private boolean validateMarker(
            MailInspectionJobState state,
            MailInspectionDispatchMarkerMessage message) {
        return message.schemaVersion()
                        == MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION
                && MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE.equals(
                        message.eventType())
                && commonIdentityMatches(
                        state,
                        message.jobInternalId(),
                        message.jobId(),
                        message.inspectionType(),
                        message.clientRequestId(),
                        message.requestFingerprint(),
                        message.businessConcurrency())
                && message.chunkCount() == state.submissionChunkCount()
                && message.chunkIndex() >= 0
                && message.chunkIndex() < message.chunkCount();
    }

    private boolean validateWork(
            MailInspectionJobState state,
            MailInspectionWorkMessage message) {
        boolean valid = message.schemaVersion()
                        == MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                && MailInspectionRabbitNames.EVENT_TYPE.equals(
                        message.eventType())
                && commonIdentityMatches(
                        state,
                        message.jobInternalId(),
                        message.jobId(),
                        message.inspectionType(),
                        message.clientRequestId(),
                        message.requestFingerprint(),
                        message.businessConcurrency())
                && message.requestedCount() == state.requestedCount()
                && message.acceptedCount() == state.acceptedCount()
                && message.duplicateCount() == state.duplicateCount()
                && message.invalidCount() == state.invalidCount()
                && message.sourceChunkIndex() != null
                && message.sourceChunkIndex() >= 0
                && message.sourceChunkIndex() < state.submissionChunkCount()
                && message.lineNumber() >= 1;
        if (valid) {
            workProtector.unprotect(
                    message.messageId(),
                    message.jobId(),
                    message.inspectionType(),
                    message.lineNumber(),
                    message.protectedPayload());
        }
        return valid;
    }

    private boolean commonIdentityMatches(
            MailInspectionJobState state,
            long internalId,
            String jobId,
            MailInspectionType type,
            String clientRequestId,
            String requestFingerprint,
            int businessConcurrency) {
        return internalId == state.internalId()
                && publicIdCodec.encode(internalId).equals(jobId)
                && state.publicId().equals(jobId)
                && state.type() == type
                && state.businessConcurrency() == businessConcurrency
                && Objects.equals(state.clientRequestId(), clientRequestId)
                && state.requestFingerprint() != null
                && state.requestFingerprint().value().equals(
                        requestFingerprint);
    }

    private void requeueUnsettled(
            MailInspectionType type,
            MailInspectionRecoverySession session,
            Set<Long> deliveryTags) {
        if (session == null || deliveryTags.isEmpty()) {
            return;
        }
        try {
            for (Long deliveryTag : List.copyOf(deliveryTags)) {
                session.channel().basicNack(deliveryTag, false, true);
                deliveryTags.remove(deliveryTag);
            }
        } catch (Exception ignored) {
            try {
                recoveryObserver.nackRequeueFailed(type);
            } catch (RuntimeException metricsFailure) {
                // 指标异常不能阻止物理连接关闭后由 Rabbit 归还全部 Unacked。
            }
            session.forceClose();
        }
    }

    private static void close(MailInspectionRecoverySession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
