package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionListenerControl;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryCoordinator;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryObserver;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionTypeLifecycleGuard;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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
 * 在启动时以 Redis 文档恢复监听器，并拒绝“Rabbit 有消息但 Redis 无权威任务”的不一致状态。
 *
 * <p>恢复不再从 Rabbit 重建任务或双读旧 Schema；Redis 缺失时该检查类型保持 UNAVAILABLE，防止无状态消息触发邮箱业务。</p>
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

    private final MailInspectionRecoveryConnectionFactory connectionFactory;
    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionSubmissionListenerControl submissionControl;
    private final MailInspectionListenerControl workControl;
    private final MailInspectionTypeLifecycleGuard lifecycleGuard;
    private final MailInspectionRecoveryObserver observer;

    public MailInspectionRecoveryCoordinatorImpl(
            MailInspectionRecoveryConnectionFactory connectionFactory,
            AdminMailInspectionJobStore jobStore,
            MailInspectionSubmissionListenerControl submissionControl,
            MailInspectionListenerControl workControl,
            MailInspectionTypeLifecycleGuard lifecycleGuard,
            MailInspectionRecoveryObserver observer) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.jobStore = Objects.requireNonNull(jobStore);
        this.submissionControl = Objects.requireNonNull(submissionControl);
        this.workControl = Objects.requireNonNull(workControl);
        this.lifecycleGuard = Objects.requireNonNull(lifecycleGuard);
        this.observer = Objects.requireNonNull(observer);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterApplicationReady() {
        recoverAll().subscribe(
                ignored -> { },
                failure -> LOGGER.warn(
                        "event={} exceptionType={}",
                        "admin_mail_inspection_recovery_terminated",
                        failure.getClass().getName()));
    }

    @Override
    public Mono<Void> recoverAll() {
        return Flux.fromIterable(MailInspectionRabbitNames.supportedTypes())
                .concatMap(this::recoverType)
                .then();
    }

    private Mono<Void> recoverType(MailInspectionType type) {
        long started = System.nanoTime();
        return Mono.fromCallable(() -> lifecycleGuard.withLock(
                        type,
                        () -> inspect(type)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(plan -> startForPlan(type, plan))
                .doOnSuccess(ignored -> {
                    jobStore.startAccepting(type);
                    observe(type, true, started);
                })
                .onErrorResume(exception -> {
                    submissionControl.stop(type);
                    workControl.stop(type);
                    try {
                        jobStore.markUnavailable(
                                type,
                                "REDIS_RABBIT_RECOVERY_INCONSISTENT");
                    } catch (RuntimeException ignored) {
                        // Redis 本身不可用时无法持久化闸门，但监听器已停止，后续创建仍会因 Redis 操作失败而返回 503。
                    }
                    observe(type, false, started);
                    LOGGER.warn(
                            "event={} inspectionType={} exceptionType={}",
                            "admin_mail_inspection_recovery_type_unavailable",
                            type,
                            exception.getClass().getName());
                    return Mono.empty();
                });
    }

    private RecoveryPlan inspect(MailInspectionType type) {
        jobStore.markRecovering(type);
        Optional<MailInspectionRedisJobDocument> active =
                jobStore.findActiveByType(type);
        try (MailInspectionRecoverySession session =
                     connectionFactory.open(type, "redis-authority-check")) {
            int submissionReady = session.channel()
                    .queueDeclarePassive(
                            MailInspectionRabbitNames.submissionQueue(type))
                    .getMessageCount();
            int workReady = session.channel()
                    .queueDeclarePassive(MailInspectionRabbitNames.queue(type))
                    .getMessageCount();
            int markerReady = session.channel()
                    .queueDeclarePassive(
                            MailInspectionRabbitNames.dispatchStateQueue(type))
                    .getMessageCount();
            int ready = submissionReady + workReady + markerReady;
            if (active.isEmpty() && ready > 0) {
                // 无 Redis 权威文档时禁止从 Rabbit 载荷反向重建任务，避免旧 Schema 或孤儿消息执行业务。
                throw new IllegalStateException(
                        "Rabbit contains mail work without Redis authority");
            }
            return new RecoveryPlan(
                    active.orElse(null),
                    submissionReady,
                    workReady);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "mail inspection Rabbit recovery inspection failed",
                    exception);
        }
    }

    private Mono<Void> startForPlan(
            MailInspectionType type, RecoveryPlan plan) {
        MailInspectionRedisJobDocument document = plan.document();
        if (document == null) {
            return Mono.empty();
        }
        if ((document.status() == MailInspectionJobStatus.DISPATCHING
                || document.status()
                == MailInspectionJobStatus.AWAITING_CLIENT_RESUBMISSION)
                && plan.submissionReady() > 0) {
            return submissionControl.start(type);
        }
        if ((document.status() == MailInspectionJobStatus.RUNNING
                || document.status() == MailInspectionJobStatus.QUEUED)
                && plan.workReady() > 0) {
            return workControl
                    .prepare(type, document.businessConcurrency())
                    .then(workControl.start(
                            type, document.businessConcurrency()));
        }
        return Mono.empty();
    }

    private void observe(
            MailInspectionType type,
            boolean successful,
            long startedNanos) {
        try {
            observer.recoveryCompleted(
                    type,
                    successful,
                    Duration.ofNanos(System.nanoTime() - startedNanos));
        } catch (RuntimeException ignored) {
            // 指标失败不能改变 Redis 接收闸门或 Rabbit 监听器恢复结果。
        }
    }

    private record RecoveryPlan(
            MailInspectionRedisJobDocument document,
            int submissionReady,
            int workReady) {
    }
}
