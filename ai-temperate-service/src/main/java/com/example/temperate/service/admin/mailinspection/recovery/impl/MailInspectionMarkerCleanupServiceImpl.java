package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionMarkerCleanupService;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.QueueKind;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.ScannedMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 使用按类型锁与 Rabbit 物理会话分批回收完整终态 Marker，防止历史任务再次阻塞启动恢复。
 *
 * <p>活动、未知、不完整或校验失败的 Marker 一律重新入队；清理失败不会关闭其他类型的创建闸门。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionMarkerCleanupServiceImpl
        implements MailInspectionMarkerCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MailInspectionMarkerCleanupServiceImpl.class);

    private final MailInspectionRecoveryConnectionFactory connectionFactory;
    private final MailInspectionRecoveryPlanner recoveryPlanner;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;
    private final MailInspectionRecoveryObserver recoveryObserver;
    private final ObjectMapper objectMapper;
    private final AdminMailInspectionJobStore jobStore;
    private final PublicIdCodec publicIdCodec;
    private final AdminMailInspectionProperties properties;

    public MailInspectionMarkerCleanupServiceImpl(
            MailInspectionRecoveryConnectionFactory connectionFactory,
            MailInspectionRecoveryPlanner recoveryPlanner,
            MailInspectionTypeLifecycleGuard lifecycleGuard,
            MailInspectionRecoveryObserver recoveryObserver,
            ObjectMapper objectMapper,
            AdminMailInspectionJobStore jobStore,
            PublicIdCodec publicIdCodec,
            AdminMailInspectionProperties properties) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.recoveryPlanner = Objects.requireNonNull(recoveryPlanner);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
        this.recoveryObserver = Objects.requireNonNull(recoveryObserver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.jobStore = Objects.requireNonNull(jobStore);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    @Scheduled(
            fixedDelayString =
                    "${app.admin.mail-inspection.rabbit.marker-cleanup-interval:PT1M}")
    public void cleanupTerminalMarkers() {
        for (MailInspectionType type :
                MailInspectionRabbitNames.supportedTypes()) {
            lifecycleGuard.withLock(
                    type,
                    () -> cleanupType(type));
        }
    }

    private void cleanupType(MailInspectionType type) {
        if (jobStore.findActiveByType(type).isPresent()) {
            return;
        }
        MailInspectionRecoverySession session = null;
        LinkedHashSet<Long> unsettled = new LinkedHashSet<>();
        int scannedCount = 0;
        int ackedCount = 0;
        try {
            session = connectionFactory.open(type, "marker-cleanup");
            Channel channel = session.channel();
            // 只有 Submission 与 Work 均无 Ready 时才允许把完整 Marker 视为历史终态账本。
            if (channel.queueDeclarePassive(
                            MailInspectionRabbitNames.submissionQueue(type))
                            .getMessageCount() > 0
                    || channel.queueDeclarePassive(
                                    MailInspectionRabbitNames.queue(type))
                            .getMessageCount() > 0) {
                return;
            }

            List<ScannedMessage> messages = new ArrayList<>();
            int batchSize = properties.rabbit()
                    .markerCleanupBatchSize();
            int markerReady = channel.queueDeclarePassive(
                            MailInspectionRabbitNames
                                    .dispatchStateQueue(type))
                    .getMessageCount();
            observeMarkerQueueSafely(
                    type,
                    markerReady,
                    0);
            for (int index = 0; index < batchSize; index++) {
                GetResponse response = channel.basicGet(
                        MailInspectionRabbitNames.dispatchStateQueue(type),
                        false);
                if (response == null) {
                    break;
                }
                long deliveryTag =
                        response.getEnvelope().getDeliveryTag();
                unsettled.add(deliveryTag);
                scannedCount++;
                MailInspectionDispatchMarkerMessage marker =
                        objectMapper.readValue(
                                response.getBody(),
                                MailInspectionDispatchMarkerMessage.class);
                messages.add(new ScannedMessage(
                        deliveryTag,
                        QueueKind.MARKER,
                        marker));
            }
            observeMarkerQueueSafely(
                    type,
                    channel.queueDeclarePassive(
                                    MailInspectionRabbitNames
                                            .dispatchStateQueue(type))
                            .getMessageCount(),
                    unsettled.size());

            Map<MailInspectionRecoveryPlanner.JobKey, List<ScannedMessage>>
                    grouped = recoveryPlanner.groupByJob(messages);
            for (List<ScannedMessage> group : grouped.values()) {
                boolean complete = validateCompleteGroup(type, group);
                for (ScannedMessage message : group) {
                    if (complete) {
                        channel.basicAck(message.deliveryTag(), false);
                        ackedCount++;
                    } else {
                        channel.basicNack(
                                message.deliveryTag(),
                                false,
                                true);
                    }
                    unsettled.remove(message.deliveryTag());
                }
            }
            if (!unsettled.isEmpty()) {
                throw new IllegalStateException(
                        "mail inspection marker cleanup left unsettled deliveries");
            }
            if (ackedCount > 0) {
                observeMarkersCleanedSafely(type, ackedCount);
                LOGGER.info(
                        "event={} inspectionType={} scannedCount={} "
                                + "ackedCount={}",
                        "admin_mail_inspection_terminal_markers_cleaned",
                        type,
                        scannedCount,
                        ackedCount);
            }
            observeMarkerQueueSafely(
                    type,
                    channel,
                    0);
        } catch (Exception exception) {
            requeueUnsettled(type, session, unsettled);
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "scannedCount={} ackedCount={} exceptionType={} "
                            + "rootCauseType={}",
                    "admin_mail_inspection_marker_cleanup_failed",
                    type,
                    "MARKER_CLEANUP_SCAN",
                    scannedCount,
                    ackedCount,
                    exception.getClass().getName(),
                    rootCauseType(exception));
        } finally {
            close(session);
        }
    }

    private boolean validateCompleteGroup(
            MailInspectionType expectedType,
            List<ScannedMessage> group) {
        if (group.isEmpty()) {
            return false;
        }
        MailInspectionDispatchMarkerMessage first =
                (MailInspectionDispatchMarkerMessage)
                        group.getFirst().body();
        if (!validEnvelope(expectedType, first)) {
            return false;
        }
        Set<Integer> chunkIndexes = new HashSet<>();
        for (ScannedMessage item : group) {
            if (!(item.body()
                    instanceof MailInspectionDispatchMarkerMessage marker)
                    || !sameIdentity(first, marker)
                    || !validEnvelope(expectedType, marker)) {
                return false;
            }
            chunkIndexes.add(marker.chunkIndex());
        }
        if (chunkIndexes.size() != first.chunkCount()) {
            return false;
        }
        for (int index = 0; index < first.chunkCount(); index++) {
            if (!chunkIndexes.contains(index)) {
                return false;
            }
        }
        return true;
    }

    private boolean validEnvelope(
            MailInspectionType expectedType,
            MailInspectionDispatchMarkerMessage marker) {
        try {
            return marker.schemaVersion()
                            == MailInspectionRabbitNames
                                    .DISPATCH_MARKER_SCHEMA_VERSION
                    && MailInspectionRabbitNames
                            .DISPATCH_MARKER_EVENT_TYPE
                            .equals(marker.eventType())
                    && marker.inspectionType() == expectedType
                    && marker.jobInternalId() > 0
                    && publicIdCodec.encode(marker.jobInternalId())
                            .equals(marker.jobId())
                    && marker.clientRequestId().matches(
                            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                    && new MailInspectionRequestFingerprint(
                            marker.requestFingerprint()).value()
                            .equals(marker.requestFingerprint())
                    && marker.businessConcurrency() >= 1
                    && marker.businessConcurrency()
                            <= properties.job()
                                    .maxBusinessConcurrency()
                    && marker.chunkCount() >= 1
                    && marker.chunkIndex() >= 0
                    && marker.chunkIndex() < marker.chunkCount();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameIdentity(
            MailInspectionDispatchMarkerMessage expected,
            MailInspectionDispatchMarkerMessage actual) {
        return expected.jobInternalId() == actual.jobInternalId()
                && expected.jobId().equals(actual.jobId())
                && expected.inspectionType() == actual.inspectionType()
                && expected.clientRequestId().equals(
                        actual.clientRequestId())
                && expected.requestFingerprint().equals(
                        actual.requestFingerprint())
                && expected.createdAt().equals(actual.createdAt())
                && expected.chunkCount() == actual.chunkCount()
                && expected.businessConcurrency()
                        == actual.businessConcurrency();
    }

    private void requeueUnsettled(
            MailInspectionType type,
            MailInspectionRecoverySession session,
            Set<Long> unsettled) {
        if (session == null || unsettled.isEmpty()) {
            return;
        }
        try {
            for (Long deliveryTag : List.copyOf(unsettled)) {
                session.channel().basicNack(
                        deliveryTag,
                        false,
                        true);
                unsettled.remove(deliveryTag);
            }
        } catch (Exception exception) {
            observeNackFailureSafely(type);
            session.forceClose();
        }
    }

    private void observeMarkersCleanedSafely(
            MailInspectionType type,
            int count) {
        try {
            recoveryObserver.markersCleaned(type, count);
        } catch (RuntimeException ignored) {
            // Marker 已经确认删除，指标异常不能改变 Rabbit 结算结果。
        }
    }

    private void observeMarkerQueueSafely(
            MailInspectionType type,
            int ready,
            int unacked) {
        try {
            recoveryObserver.markerQueueObserved(type, ready, unacked);
        } catch (RuntimeException ignored) {
            // 指标采集不参与 Marker 是否可删除的安全判定。
        }
    }

    private void observeMarkerQueueSafely(
            MailInspectionType type,
            Channel channel,
            int unacked) {
        try {
            observeMarkerQueueSafely(
                    type,
                    channel.queueDeclarePassive(
                                    MailInspectionRabbitNames
                                            .dispatchStateQueue(type))
                            .getMessageCount(),
                    unacked);
        } catch (Exception ignored) {
            // 队列计数只用于观测，不能反向改变已完成的 Marker 结算。
        }
    }

    private void observeNackFailureSafely(MailInspectionType type) {
        try {
            recoveryObserver.nackRequeueFailed(type);
        } catch (RuntimeException ignored) {
            // 物理连接关闭仍是最终保障，指标异常不得中断该路径。
        }
    }

    private static void close(
            MailInspectionRecoverySession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private static String rootCauseType(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited =
                java.util.Collections.newSetFromMap(
                        new IdentityHashMap<>());
        while (current.getCause() != null
                && visited.add(current)
                && !visited.contains(current.getCause())) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }
}
