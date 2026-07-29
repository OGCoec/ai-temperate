package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.AdminMailInspectionSubmissionPayloadProtector;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionProtectedCredential;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionCredential;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryCoordinator;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.QueueKind;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.ScannedMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 使用 Rabbit 原生物理会话按任务分组恢复 Submission、Marker 与 Work，并按检查类型独立开放创建闸门。
 *
 * <p>恢复只重建暂停快照，不启动 OAuth、IMAP 或监听器；失败时逐条 NACK 未结算消息，物理连接关闭提供第二重归还保障。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionRecoveryCoordinatorImpl
        implements MailInspectionRecoveryCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MailInspectionRecoveryCoordinatorImpl.class);
    private static final List<Long> TRANSIENT_RETRY_DELAYS_MILLIS =
            List.of(1_000L, 2_000L, 5_000L);

    private final MailInspectionRecoveryConnectionFactory connectionFactory;
    private final MailInspectionRecoveryPlanner recoveryPlanner;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;
    private final MailInspectionRecoveryObserver recoveryObserver;
    private final ObjectMapper objectMapper;
    private final AdminMailInspectionPayloadProtector workProtector;
    private final AdminMailInspectionSubmissionPayloadProtector submissionProtector;
    private final AdminMailInspectionJobStore jobStore;
    private final PublicIdCodec publicIdCodec;
    private final AdminMailInspectionProperties properties;
    private final Clock clock;

    public MailInspectionRecoveryCoordinatorImpl(
            MailInspectionRecoveryConnectionFactory connectionFactory,
            MailInspectionRecoveryPlanner recoveryPlanner,
            MailInspectionTypeLifecycleGuard lifecycleGuard,
            MailInspectionRecoveryObserver recoveryObserver,
            ObjectMapper objectMapper,
            AdminMailInspectionPayloadProtector workProtector,
            AdminMailInspectionSubmissionPayloadProtector submissionProtector,
            AdminMailInspectionJobStore jobStore,
            PublicIdCodec publicIdCodec,
            AdminMailInspectionProperties properties,
            Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.recoveryPlanner = Objects.requireNonNull(recoveryPlanner);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
        this.recoveryObserver = Objects.requireNonNull(recoveryObserver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.workProtector = Objects.requireNonNull(workProtector);
        this.submissionProtector = Objects.requireNonNull(submissionProtector);
        this.jobStore = Objects.requireNonNull(jobStore);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * ApplicationReady 只异步触发恢复；顶层异常必须留下稳定日志，禁止静默吞掉。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterApplicationReady() {
        recoverAll().subscribe(
                ignored -> { },
                failure -> LOGGER.warn(
                        "event={} failurePoint={} exceptionType={} "
                                + "rootCauseType={}",
                        "admin_mail_inspection_recovery_terminated",
                        RecoveryFailurePoint.RECOVERY_TOP_LEVEL,
                        failure.getClass().getName(),
                        rootCauseType(failure)));
    }

    @Override
    public Mono<Void> recoverAll() {
        return Mono.defer(() -> {
            String attemptId = UUID.randomUUID().toString();
            long startedNanos = System.nanoTime();
            return Flux.fromIterable(MailInspectionRabbitNames.supportedTypes())
                    .sort(Comparator.comparing(MailInspectionType::name))
                    .concatMap(type -> Mono.fromCallable(() ->
                                    lifecycleGuard.withLock(
                                            type,
                                            () -> recoverType(
                                                    type,
                                                    attemptId)))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .collectList()
                    .doOnSuccess(results -> logRecoverySummary(
                            results,
                            startedNanos))
                    .then();
        });
    }

    private RecoveryTypeResult recoverType(
            MailInspectionType type,
            String attemptId) {
        long startedNanos = System.nanoTime();
        try {
            RecoveryTypeResult result =
                    recoverTypeWithinBoundary(type, attemptId);
            observeRecoverySafely(
                    type,
                    result.healthy(),
                    Duration.ofNanos(
                            System.nanoTime() - startedNanos));
            return result;
        } catch (RuntimeException exception) {
            safelyMarkUnavailable(
                    type,
                    RecoveryFailurePoint.RECOVERY_TOP_LEVEL.name());
            observeRecoverySafely(
                    type,
                    false,
                    Duration.ofNanos(
                            System.nanoTime() - startedNanos));
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "exceptionType={} rootCauseType={}",
                    "admin_mail_inspection_recovery_type_unavailable",
                    type,
                    RecoveryFailurePoint.RECOVERY_TOP_LEVEL,
                    exception.getClass().getName(),
                    rootCauseType(exception));
            return RecoveryTypeResult.failure(
                    type,
                    RecoveryFailurePoint.RECOVERY_TOP_LEVEL,
                    0,
                    0,
                    exception);
        }
    }

    private RecoveryTypeResult recoverTypeWithinBoundary(
            MailInspectionType type,
            String attemptId) {
        jobStore.markRecovering(type);
        long startedNanos = System.nanoTime();
        LOGGER.info(
                "event={} inspectionType={}",
                "admin_mail_inspection_recovery_type_started",
                type);
        RecoveryTypeResult result = scanWithFiniteRetry(
                type,
                attemptId);
        if (result.healthy()) {
            jobStore.startAccepting(type);
            LOGGER.info(
                    "event={} inspectionType={} scannedCount={} "
                            + "groupCount={} terminalMarkerGroupsCleared={} "
                            + "activeJobsRecovered={} elapsedMs={}",
                    "admin_mail_inspection_recovery_type_ready",
                    type,
                    result.scannedCount(),
                    result.groupCount(),
                    result.terminalGroupsCleared(),
                    result.activeJobsRecovered(),
                    elapsedMillis(startedNanos));
            return result;
        }
        safelyMarkUnavailable(type, result.failurePoint().name());
        LOGGER.warn(
                "event={} inspectionType={} failurePoint={} "
                        + "scannedCount={} groupCount={} exceptionType={} "
                        + "rootCauseType={} elapsedMs={}",
                "admin_mail_inspection_recovery_type_unavailable",
                type,
                result.failurePoint(),
                result.scannedCount(),
                result.groupCount(),
                result.exceptionType(),
                result.rootCauseType(),
                elapsedMillis(startedNanos));
        return result;
    }

    /**
     * 单个类型的状态记录失败也不能终止后续类型恢复；初始 RECOVERING 状态会继续阻止该类型创建任务。
     */
    private void safelyMarkUnavailable(
            MailInspectionType type,
            String failurePoint) {
        try {
            jobStore.markUnavailable(type, failurePoint);
        } catch (RuntimeException stateFailure) {
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "exceptionType={} rootCauseType={}",
                    "admin_mail_inspection_recovery_type_unavailable",
                    type,
                    "ACCEPTANCE_STATE_UPDATE",
                    stateFailure.getClass().getName(),
                    rootCauseType(stateFailure));
        }
    }

    /**
     * 指标系统只负责观察；注册表或采集器异常不得改变 Rabbit 消息结算和类型接收状态。
     */
    private void observeRecoverySafely(
            MailInspectionType type,
            boolean successful,
            Duration elapsed) {
        try {
            recoveryObserver.recoveryCompleted(
                    type,
                    successful,
                    elapsed);
        } catch (RuntimeException metricsFailure) {
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "exceptionType={} rootCauseType={}",
                    "admin_mail_inspection_recovery_observer_failed",
                    type,
                    "RECOVERY_METRICS",
                    metricsFailure.getClass().getName(),
                    rootCauseType(metricsFailure));
        }
    }

    private void observeMarkerQueueSafely(
            MailInspectionType type,
            Channel channel,
            int unacked) {
        try {
            int ready = channel.queueDeclarePassive(
                            MailInspectionRabbitNames
                                    .dispatchStateQueue(type))
                    .getMessageCount();
            recoveryObserver.markerQueueObserved(
                    type,
                    ready,
                    unacked);
        } catch (Exception ignored) {
            // 指标失败不能把已经通过安全校验的 Rabbit 恢复降级为业务失败。
        }
    }

    private void observeNackFailureSafely(MailInspectionType type) {
        try {
            recoveryObserver.nackRequeueFailed(type);
        } catch (RuntimeException ignored) {
            // 物理连接已经执行强制关闭，指标异常不能覆盖消息归还保障。
        }
    }

    private RecoveryTypeResult scanWithFiniteRetry(
            MailInspectionType type,
            String attemptId) {
        RecoveryScanException last = null;
        for (int attempt = 1;
                attempt <= TRANSIENT_RETRY_DELAYS_MILLIS.size() + 1;
                attempt++) {
            try {
                return scanOnce(type, attemptId);
            } catch (RecoveryScanException exception) {
                last = exception;
                if (!isRetryableRabbitStage(exception.failurePoint())
                        || !isTransientRabbitFailure(exception.getCause())
                        || attempt
                                > TRANSIENT_RETRY_DELAYS_MILLIS.size()) {
                    break;
                }
                sleepBeforeRetry(
                        TRANSIENT_RETRY_DELAYS_MILLIS.get(attempt - 1));
            }
        }
        Objects.requireNonNull(last);
        return RecoveryTypeResult.failure(
                type,
                last.failurePoint(),
                last.scannedCount(),
                last.groupCount(),
                last.getCause());
    }

    private RecoveryTypeResult scanOnce(
            MailInspectionType type,
            String attemptId) {
        MailInspectionRecoverySession session = null;
        LinkedHashSet<Long> unsettled = new LinkedHashSet<>();
        List<ScannedMessage> scanned = new ArrayList<>();
        RecoveryProgress progress = new RecoveryProgress();
        int groupCount = 0;
        try {
            progress.moveTo(RecoveryFailurePoint.RECOVERY_CONNECTION_CREATE);
            session = connectionFactory.open(type, "startup");
            Channel channel = session.channel();
            progress.moveTo(RecoveryFailurePoint.RECOVERY_BASIC_GET);
            scanQueue(
                    channel,
                    QueueKind.SUBMISSION,
                    MailInspectionRabbitNames.submissionQueue(type),
                    scanned,
                    unsettled,
                    progress);
            scanQueue(
                    channel,
                    QueueKind.MARKER,
                    MailInspectionRabbitNames.dispatchStateQueue(type),
                    scanned,
                    unsettled,
                    progress);
            scanQueue(
                    channel,
                    QueueKind.WORK,
                    MailInspectionRabbitNames.queue(type),
                    scanned,
                    unsettled,
                    progress);
            int markerUnacked = (int) scanned.stream()
                    .filter(item -> item.kind() == QueueKind.MARKER)
                    .count();
            observeMarkerQueueSafely(
                    type,
                    channel,
                    markerUnacked);
            if (scanned.isEmpty()) {
                return RecoveryTypeResult.success(type, 0, 0, 0, 0);
            }

            progress.moveTo(RecoveryFailurePoint.RECOVERY_GROUP_PLAN);
            Map<MailInspectionRecoveryPlanner.JobKey, List<ScannedMessage>>
                    grouped = recoveryPlanner.groupByJob(scanned);
            groupCount = grouped.size();
            List<RecoveryGroupPlan> plans = new ArrayList<>(groupCount);
            for (List<ScannedMessage> group : grouped.values()) {
                plans.add(planGroup(type, group));
            }
            List<RecoveryGroupPlan> active = plans.stream()
                    .filter(plan -> !plan.terminal())
                    .toList();
            if (active.size() > 1) {
                throw new IllegalStateException(
                        "mail inspection queue contains multiple active jobs");
            }

            // JobStore 恢复必须发生在消息重新入队前；若 Store 拒绝，未结算消息仍由失败路径全部归还。
            if (!active.isEmpty()) {
                progress.moveTo(
                        RecoveryFailurePoint.RECOVERY_JOB_STORE_RESTORE);
                jobStore.restore(active.getFirst().state());
            }

            progress.moveTo(RecoveryFailurePoint.RECOVERY_MESSAGE_SETTLE);
            SettlementCounts counts = new SettlementCounts();
            for (RecoveryGroupPlan plan : plans) {
                settleGroup(channel, plan, unsettled, counts);
                LOGGER.info(
                        "event={} inspectionType={} queueKind={} "
                                + "scannedCount={} terminal={} duplicateCount={}",
                        "admin_mail_inspection_recovery_group_classified",
                        type,
                        plan.queueSummary(),
                        plan.messages().size(),
                        plan.terminal(),
                        plan.duplicateCount());
            }
            if (!unsettled.isEmpty()) {
                throw new IllegalStateException(
                        "mail inspection recovery left unsettled deliveries");
            }
            observeMarkerQueueSafely(
                    type,
                    channel,
                    0);
            return RecoveryTypeResult.success(
                    type,
                    scanned.size(),
                    groupCount,
                    (int) plans.stream()
                            .filter(RecoveryGroupPlan::terminal)
                            .count(),
                    active.size());
        } catch (Exception exception) {
            RecoveryFailurePoint failedAt = progress.current();
            int unsettledBeforeRequeue = unsettled.size();
            if (session != null && !unsettled.isEmpty()) {
                requeueUnsettled(
                        type,
                        session,
                        unsettled,
                        progress);
            }
            LOGGER.warn(
                    "event={} recoveryAttemptId={} inspectionType={} "
                            + "failurePoint={} scannedCount={} groupCount={} "
                            + "requeuedCount={} exceptionType={} "
                            + "rootCauseType={}",
                    "admin_mail_inspection_recovery_messages_requeued",
                    attemptId,
                    type,
                    failedAt,
                    scanned.size(),
                    groupCount,
                    unsettledBeforeRequeue - unsettled.size(),
                    exception.getClass().getName(),
                    rootCauseType(exception));
            throw new RecoveryScanException(
                    failedAt,
                    scanned.size(),
                    groupCount,
                    exception);
        } finally {
            closeSession(session);
        }
    }

    private RecoveryGroupPlan planGroup(
            MailInspectionType expectedType,
            List<ScannedMessage> messages) {
        MailInspectionJobState state = rebuild(expectedType, messages);
        boolean markerOnly = messages.stream()
                .allMatch(item -> item.kind() == QueueKind.MARKER);
        boolean terminal =
                state.status() == MailInspectionJobStatus.COMPLETED;
        if (markerOnly && !terminal) {
            throw new IllegalStateException(
                    "mail inspection marker ledger is incomplete");
        }
        int duplicateCount = countDuplicates(messages);
        String queueSummary = messages.stream()
                .map(item -> item.kind().name())
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("EMPTY");
        return new RecoveryGroupPlan(
                state,
                messages,
                terminal,
                duplicateCount,
                queueSummary);
    }

    /**
     * 逐条结算避免跨越已 ACK deliveryTag 的 multiple 范围；重复消息只有在完整校验后才会被 ACK。
     */
    private static void settleGroup(
            Channel channel,
            RecoveryGroupPlan plan,
            Set<Long> unsettled,
            SettlementCounts counts) throws Exception {
        Set<Integer> dispatchedChunkIndexes = plan.messages().stream()
                .filter(item -> item.kind() == QueueKind.MARKER)
                .map(item -> (MailInspectionDispatchMarkerMessage) item.body())
                .map(MailInspectionDispatchMarkerMessage::chunkIndex)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> canonical = new HashSet<>();
        for (ScannedMessage item : plan.messages()) {
            boolean duplicate = !canonical.add(deduplicationKey(item));
            boolean redundantSubmission =
                    item.kind() == QueueKind.SUBMISSION
                            && dispatchedChunkIndexes.contains(
                                    ((MailInspectionSubmissionChunkMessage)
                                            item.body()).chunkIndex());
            if (plan.terminal() || duplicate || redundantSubmission) {
                channel.basicAck(item.deliveryTag(), false);
                unsettled.remove(item.deliveryTag());
                counts.acked++;
                continue;
            }
            channel.basicNack(item.deliveryTag(), false, true);
            unsettled.remove(item.deliveryTag());
            counts.requeued++;
        }
    }

    private void scanQueue(
            Channel channel,
            QueueKind kind,
            String queue,
            List<ScannedMessage> target,
            Set<Long> unsettled,
            RecoveryProgress progress) throws Exception {
        for (;;) {
            progress.moveTo(RecoveryFailurePoint.RECOVERY_BASIC_GET);
            GetResponse response = channel.basicGet(queue, false);
            if (response == null) {
                return;
            }
            long deliveryTag = response.getEnvelope().getDeliveryTag();
            // deliveryTag 必须先登记再反序列化，否则坏 JSON 会成为无法显式归还的 Unacked。
            unsettled.add(deliveryTag);
            progress.moveTo(
                    RecoveryFailurePoint.RECOVERY_MESSAGE_DESERIALIZE);
            Object body = switch (kind) {
                case SUBMISSION -> objectMapper.readValue(
                        response.getBody(),
                        MailInspectionSubmissionChunkMessage.class);
                case MARKER -> objectMapper.readValue(
                        response.getBody(),
                        MailInspectionDispatchMarkerMessage.class);
                case WORK -> objectMapper.readValue(
                        response.getBody(),
                        MailInspectionWorkMessage.class);
            };
            target.add(new ScannedMessage(deliveryTag, kind, body));
        }
    }

    private void requeueUnsettled(
            MailInspectionType type,
            MailInspectionRecoverySession session,
            Set<Long> unsettled,
            RecoveryProgress progress) {
        progress.moveTo(RecoveryFailurePoint.RECOVERY_FAILURE_REQUEUE);
        int requeued = 0;
        try {
            for (Long deliveryTag : List.copyOf(unsettled)) {
                session.channel().basicNack(deliveryTag, false, true);
                unsettled.remove(deliveryTag);
                requeued++;
            }
            LOGGER.info(
                    "event={} inspectionType={} requeuedCount={}",
                    "admin_mail_inspection_recovery_messages_requeued",
                    type,
                    requeued);
        } catch (Exception nackFailure) {
            // 强制关闭物理 Connection 是 NACK 失败后的最终保障，Rabbit 会回收该连接全部未确认消息。
            session.forceClose();
            observeNackFailureSafely(type);
            LOGGER.warn(
                    "event={} inspectionType={} failurePoint={} "
                            + "requeuedCount={} remainingCount={} "
                            + "exceptionType={} rootCauseType={}",
                    "admin_mail_inspection_recovery_messages_requeued",
                    type,
                    RecoveryFailurePoint.RECOVERY_FAILURE_REQUEUE,
                    requeued,
                    unsettled.size(),
                    nackFailure.getClass().getName(),
                    rootCauseType(nackFailure));
        }
    }

    private MailInspectionJobState rebuild(
            MailInspectionType expectedType,
            List<ScannedMessage> scanned) {
        List<MailInspectionSubmissionChunkMessage> submissions = scanned.stream()
                .filter(item -> item.kind() == QueueKind.SUBMISSION)
                .map(item -> (MailInspectionSubmissionChunkMessage) item.body())
                .toList();
        List<MailInspectionDispatchMarkerMessage> markers = scanned.stream()
                .filter(item -> item.kind() == QueueKind.MARKER)
                .map(item -> (MailInspectionDispatchMarkerMessage) item.body())
                .toList();
        List<MailInspectionWorkMessage> work = scanned.stream()
                .filter(item -> item.kind() == QueueKind.WORK)
                .map(item -> (MailInspectionWorkMessage) item.body())
                .toList();

        if (submissions.isEmpty()
                && markers.isEmpty()
                && work.stream().allMatch(value -> value.schemaVersion()
                        == MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION)) {
            return rebuildLegacy(expectedType, work);
        }
        SubmissionIdentity identity = identity(submissions, markers, work);
        validateIdentity(expectedType, identity);
        Map<Integer, MailInspectionPendingItem> pending = new TreeMap<>();
        Set<Integer> confirmed = new HashSet<>();
        Set<Integer> dispatched = new HashSet<>();

        for (MailInspectionDispatchMarkerMessage marker : markers) {
            validateMarker(identity, marker);
            confirmed.add(marker.chunkIndex());
            dispatched.add(marker.chunkIndex());
        }
        for (MailInspectionSubmissionChunkMessage message : submissions) {
            validateSubmission(identity, message);
            confirmed.add(message.chunkIndex());
            List<MailInspectionSubmissionCredential> credentials =
                    submissionProtector.unprotect(message);
            if (dispatched.contains(message.chunkIndex())) {
                continue;
            }
            for (MailInspectionSubmissionCredential credential :
                    credentials) {
                pending.putIfAbsent(
                        credential.lineNumber(),
                        new MailInspectionPendingItem(
                                credential.lineNumber(),
                                maskEmail(credential.email())));
            }
        }
        for (MailInspectionWorkMessage message : work) {
            validateWork(expectedType, identity, message);
            MailInspectionProtectedCredential credential =
                    workProtector.unprotect(
                            message.messageId(),
                            message.jobId(),
                            message.inspectionType(),
                            message.lineNumber(),
                            message.protectedPayload());
            pending.putIfAbsent(
                    message.lineNumber(),
                    new MailInspectionPendingItem(
                            message.lineNumber(),
                            maskEmail(credential.email())));
        }

        boolean complete = hasAllChunkIndexes(
                confirmed,
                identity.chunkCount());
        MailInspectionJobState state =
                MailInspectionJobState.recoveredSubmission(
                        identity.internalId(),
                        identity.jobId(),
                        identity.type(),
                        identity.requestedCount(),
                        identity.acceptedCount(),
                        identity.duplicateCount(),
                        identity.invalidCount(),
                        identity.businessConcurrency(),
                        identity.clientRequestId(),
                        new MailInspectionRequestFingerprint(
                                identity.fingerprint()),
                        identity.chunkCount(),
                        confirmed,
                        dispatched,
                        identity.createdAt(),
                        clock.instant(),
                        properties.submission().incompleteRetention(),
                        List.copyOf(pending.values()),
                        complete);
        if (complete
                && work.isEmpty()
                && pending.isEmpty()
                && dispatched.size() == identity.chunkCount()) {
            state.complete(clock.instant(), properties.job().retention());
        }
        return state;
    }

    private MailInspectionJobState rebuildLegacy(
            MailInspectionType expectedType,
            List<MailInspectionWorkMessage> work) {
        if (work.isEmpty()) {
            throw new IllegalStateException(
                    "mail inspection marker identity is unavailable");
        }
        MailInspectionWorkMessage first = work.getFirst();
        validateLegacyWork(expectedType, first);
        Map<Integer, MailInspectionPendingItem> pending = new TreeMap<>();
        for (MailInspectionWorkMessage message : work) {
            validateLegacyWork(expectedType, message);
            if (message.jobInternalId() != first.jobInternalId()
                    || !message.jobId().equals(first.jobId())
                    || message.businessConcurrency()
                            != first.businessConcurrency()) {
                throw new IllegalStateException(
                        "mail inspection legacy group identity is inconsistent");
            }
            MailInspectionProtectedCredential credential =
                    workProtector.unprotect(
                            message.messageId(),
                            message.jobId(),
                            message.inspectionType(),
                            message.lineNumber(),
                            message.protectedPayload());
            pending.putIfAbsent(
                    message.lineNumber(),
                    new MailInspectionPendingItem(
                            message.lineNumber(),
                            maskEmail(credential.email())));
        }
        return MailInspectionJobState.recovered(
                first.jobInternalId(),
                first.jobId(),
                first.inspectionType(),
                first.requestedCount(),
                first.acceptedCount(),
                first.duplicateCount(),
                first.invalidCount(),
                first.businessConcurrency(),
                first.createdAt(),
                clock.instant(),
                List.copyOf(pending.values()));
    }

    private SubmissionIdentity identity(
            List<MailInspectionSubmissionChunkMessage> submissions,
            List<MailInspectionDispatchMarkerMessage> markers,
            List<MailInspectionWorkMessage> work) {
        if (!submissions.isEmpty()) {
            MailInspectionSubmissionChunkMessage value =
                    submissions.getFirst();
            return new SubmissionIdentity(
                    value.jobInternalId(),
                    value.jobId(),
                    value.inspectionType(),
                    value.clientRequestId(),
                    value.requestFingerprint(),
                    value.chunkCount(),
                    value.requestedCount(),
                    value.acceptedCount(),
                    value.duplicateCount(),
                    value.invalidCount(),
                    value.businessConcurrency(),
                    value.createdAt());
        }
        if (!work.isEmpty()) {
            MailInspectionWorkMessage value = work.getFirst();
            int chunkCount = markers.isEmpty()
                    ? work.stream()
                            .map(MailInspectionWorkMessage::sourceChunkIndex)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .max()
                            .orElse(0) + 1
                    : markers.getFirst().chunkCount();
            return new SubmissionIdentity(
                    value.jobInternalId(),
                    value.jobId(),
                    value.inspectionType(),
                    value.clientRequestId(),
                    value.requestFingerprint(),
                    chunkCount,
                    value.requestedCount(),
                    value.acceptedCount(),
                    value.duplicateCount(),
                    value.invalidCount(),
                    value.businessConcurrency(),
                    value.createdAt());
        }
        MailInspectionDispatchMarkerMessage value = markers.getFirst();
        return new SubmissionIdentity(
                value.jobInternalId(),
                value.jobId(),
                value.inspectionType(),
                value.clientRequestId(),
                value.requestFingerprint(),
                value.chunkCount(),
                0,
                0,
                0,
                0,
                value.businessConcurrency(),
                value.createdAt());
    }

    private void validateIdentity(
            MailInspectionType expectedType,
            SubmissionIdentity identity) {
        if (identity.type() != expectedType
                || identity.internalId() <= 0
                || !publicIdCodec.encode(identity.internalId())
                        .equals(identity.jobId())
                || identity.chunkCount() < 1
                || identity.clientRequestId() == null
                || !identity.clientRequestId().matches(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                                + "[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                || identity.businessConcurrency() < 1
                || identity.businessConcurrency()
                        > properties.job().maxBusinessConcurrency()) {
            throw new IllegalStateException(
                    "mail inspection recovery identity is invalid");
        }
        new MailInspectionRequestFingerprint(identity.fingerprint());
    }

    private static void validateSubmission(
            SubmissionIdentity identity,
            MailInspectionSubmissionChunkMessage value) {
        if (value.schemaVersion()
                        != MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION
                || !MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE.equals(
                        value.eventType())
                || !identity.matches(
                        value.jobInternalId(),
                        value.jobId(),
                        value.inspectionType(),
                        value.clientRequestId(),
                        value.requestFingerprint(),
                        value.chunkCount(),
                        value.businessConcurrency())
                || value.requestedCount() != identity.requestedCount()
                || value.acceptedCount() != identity.acceptedCount()
                || value.duplicateCount() != identity.duplicateCount()
                || value.invalidCount() != identity.invalidCount()
                || !value.createdAt().equals(identity.createdAt())
                || value.chunkIndex() < 0
                || value.chunkIndex() >= value.chunkCount()) {
            throw new IllegalStateException(
                    "mail inspection submission recovery envelope is invalid");
        }
    }

    private static void validateMarker(
            SubmissionIdentity identity,
            MailInspectionDispatchMarkerMessage value) {
        if (value.schemaVersion()
                        != MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION
                || !MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE.equals(
                        value.eventType())
                || !identity.matches(
                        value.jobInternalId(),
                        value.jobId(),
                        value.inspectionType(),
                        value.clientRequestId(),
                        value.requestFingerprint(),
                        value.chunkCount(),
                        value.businessConcurrency())
                || !value.createdAt().equals(identity.createdAt())
                || value.chunkIndex() < 0
                || value.chunkIndex() >= value.chunkCount()) {
            throw new IllegalStateException(
                    "mail inspection marker recovery envelope is invalid");
        }
    }

    private void validateWork(
            MailInspectionType expectedType,
            SubmissionIdentity identity,
            MailInspectionWorkMessage value) {
        if (value.schemaVersion()
                        != MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                || value.inspectionType() != expectedType
                || !identity.matches(
                        value.jobInternalId(),
                        value.jobId(),
                        value.inspectionType(),
                        value.clientRequestId(),
                        value.requestFingerprint(),
                        identity.chunkCount(),
                        value.businessConcurrency())
                || value.requestedCount() != identity.requestedCount()
                || value.acceptedCount() != identity.acceptedCount()
                || value.duplicateCount() != identity.duplicateCount()
                || value.invalidCount() != identity.invalidCount()
                || !value.createdAt().equals(identity.createdAt())
                || value.sourceChunkIndex() == null
                || value.sourceChunkIndex() < 0
                || value.sourceChunkIndex() >= identity.chunkCount()
                || value.lineNumber() < 1) {
            throw new IllegalStateException(
                    "mail inspection work recovery envelope is invalid");
        }
    }

    private void validateLegacyWork(
            MailInspectionType expectedType,
            MailInspectionWorkMessage value) {
        if (value.schemaVersion()
                        != MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION
                || value.inspectionType() != expectedType
                || value.jobInternalId() <= 0
                || !publicIdCodec.encode(value.jobInternalId())
                        .equals(value.jobId())
                || value.lineNumber() < 1
                || value.businessConcurrency() < 1
                || value.businessConcurrency()
                        > properties.job().maxBusinessConcurrency()) {
            throw new IllegalStateException(
                    "mail inspection legacy work envelope is invalid");
        }
    }

    private static boolean hasAllChunkIndexes(
            Set<Integer> values,
            int count) {
        if (values.size() != count) {
            return false;
        }
        for (int index = 0; index < count; index++) {
            if (!values.contains(index)) {
                return false;
            }
        }
        return true;
    }

    private static int countDuplicates(List<ScannedMessage> messages) {
        Set<String> keys = new HashSet<>();
        int duplicates = 0;
        for (ScannedMessage message : messages) {
            if (!keys.add(deduplicationKey(message))) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static String deduplicationKey(ScannedMessage message) {
        return switch (message.body()) {
            case MailInspectionSubmissionChunkMessage value ->
                    "S:" + value.chunkIndex();
            case MailInspectionDispatchMarkerMessage value ->
                    "M:" + value.chunkIndex();
            case MailInspectionWorkMessage value ->
                    "W:" + value.lineNumber();
            default -> throw new IllegalStateException(
                    "unsupported recovery message");
        };
    }

    private static String maskEmail(String email) {
        int separator = email.indexOf('@');
        if (separator <= 0 || separator == email.length() - 1) {
            return "***";
        }
        return email.substring(0, 1)
                + "***"
                + email.substring(separator);
    }

    private static void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "mail inspection recovery retry interrupted",
                    exception);
        }
    }

    private static boolean isTransientRabbitFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited =
                java.util.Collections.newSetFromMap(
                        new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof IOException
                    || current instanceof TimeoutException
                    || current instanceof ShutdownSignalException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableRabbitStage(
            RecoveryFailurePoint failurePoint) {
        return failurePoint
                        == RecoveryFailurePoint.RECOVERY_CONNECTION_CREATE
                || failurePoint == RecoveryFailurePoint.RECOVERY_BASIC_GET
                || failurePoint
                        == RecoveryFailurePoint.RECOVERY_MESSAGE_SETTLE
                || failurePoint
                        == RecoveryFailurePoint.RECOVERY_FAILURE_REQUEUE
                || failurePoint
                        == RecoveryFailurePoint.RECOVERY_SESSION_CLOSE;
    }

    private static void closeSession(
            MailInspectionRecoverySession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception exception) {
            LOGGER.warn(
                    "event={} failurePoint={} exceptionType={} "
                            + "rootCauseType={}",
                    "admin_mail_inspection_recovery_session_close_failed",
                    RecoveryFailurePoint.RECOVERY_SESSION_CLOSE,
                    exception.getClass().getName(),
                    rootCauseType(exception));
        }
    }

    private static void logRecoverySummary(
            List<RecoveryTypeResult> results,
            long startedNanos) {
        List<String> accepted = results.stream()
                .filter(RecoveryTypeResult::healthy)
                .map(result -> result.type().name())
                .toList();
        List<String> unavailable = results.stream()
                .filter(result -> !result.healthy())
                .map(result -> result.type().name())
                .toList();
        int terminalGroups = results.stream()
                .mapToInt(RecoveryTypeResult::terminalGroupsCleared)
                .sum();
        int activeJobs = results.stream()
                .mapToInt(RecoveryTypeResult::activeJobsRecovered)
                .sum();
        LOGGER.info(
                "event={} acceptedTypes={} unavailableTypes={} "
                        + "terminalMarkerGroupsCleared={} "
                        + "activeJobsRecovered={} elapsedMs={}",
                "admin_mail_inspection_recovery_completed",
                accepted,
                unavailable,
                terminalGroups,
                activeJobs,
                elapsedMillis(startedNanos));
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(
                0L,
                (System.nanoTime() - startedNanos) / 1_000_000L);
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

    private enum RecoveryFailurePoint {
        RECOVERY_TOP_LEVEL,
        RECOVERY_CONNECTION_CREATE,
        RECOVERY_BASIC_GET,
        RECOVERY_MESSAGE_DESERIALIZE,
        RECOVERY_GROUP_PLAN,
        RECOVERY_JOB_STORE_RESTORE,
        RECOVERY_MESSAGE_SETTLE,
        RECOVERY_FAILURE_REQUEUE,
        RECOVERY_SESSION_CLOSE
    }

    /**
     * 记录当前恢复阶段，日志仅暴露稳定枚举，不泄漏消息正文或第三方异常文本。
     */
    private static final class RecoveryProgress {

        private RecoveryFailurePoint current =
                RecoveryFailurePoint.RECOVERY_CONNECTION_CREATE;

        void moveTo(RecoveryFailurePoint next) {
            current = Objects.requireNonNull(next);
        }

        RecoveryFailurePoint current() {
            return current;
        }
    }

    private static final class SettlementCounts {
        private int acked;
        private int requeued;
    }

    private record RecoveryGroupPlan(
            MailInspectionJobState state,
            List<ScannedMessage> messages,
            boolean terminal,
            int duplicateCount,
            String queueSummary) {
    }

    private record RecoveryTypeResult(
            MailInspectionType type,
            boolean healthy,
            RecoveryFailurePoint failurePoint,
            int scannedCount,
            int groupCount,
            int terminalGroupsCleared,
            int activeJobsRecovered,
            String exceptionType,
            String rootCauseType) {

        static RecoveryTypeResult success(
                MailInspectionType type,
                int scannedCount,
                int groupCount,
                int terminalGroupsCleared,
                int activeJobsRecovered) {
            return new RecoveryTypeResult(
                    type,
                    true,
                    RecoveryFailurePoint.RECOVERY_MESSAGE_SETTLE,
                    scannedCount,
                    groupCount,
                    terminalGroupsCleared,
                    activeJobsRecovered,
                    "",
                    "");
        }

        static RecoveryTypeResult failure(
                MailInspectionType type,
                RecoveryFailurePoint failurePoint,
                int scannedCount,
                int groupCount,
                Throwable failure) {
            return new RecoveryTypeResult(
                    type,
                    false,
                    failurePoint,
                    scannedCount,
                    groupCount,
                    0,
                    0,
                    failure.getClass().getName(),
                    MailInspectionRecoveryCoordinatorImpl.rootCauseType(
                            failure));
        }
    }

    private static final class RecoveryScanException
            extends RuntimeException {

        private final RecoveryFailurePoint failurePoint;
        private final int scannedCount;
        private final int groupCount;

        RecoveryScanException(
                RecoveryFailurePoint failurePoint,
                int scannedCount,
                int groupCount,
                Throwable cause) {
            super(cause);
            this.failurePoint = failurePoint;
            this.scannedCount = scannedCount;
            this.groupCount = groupCount;
        }

        RecoveryFailurePoint failurePoint() {
            return failurePoint;
        }

        int scannedCount() {
            return scannedCount;
        }

        int groupCount() {
            return groupCount;
        }
    }

    private record SubmissionIdentity(
            long internalId,
            String jobId,
            MailInspectionType type,
            String clientRequestId,
            String fingerprint,
            int chunkCount,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt) {

        boolean matches(
                long candidateInternalId,
                String candidateJobId,
                MailInspectionType candidateType,
                String candidateRequestId,
                String candidateFingerprint,
                int candidateChunkCount,
                int candidateConcurrency) {
            return internalId == candidateInternalId
                    && jobId.equals(candidateJobId)
                    && type == candidateType
                    && clientRequestId.equals(candidateRequestId)
                    && fingerprint.equals(candidateFingerprint)
                    && chunkCount == candidateChunkCount
                    && businessConcurrency == candidateConcurrency;
        }
    }
}
