package com.example.temperate.web.admin.mailinspection.config;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionListenerControl;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 Spring Rabbit 监听容器注册表控制四类邮箱工作消费者，并把每个任务锁定的业务并发转换为消费者额度。
 *
 * <p>停止和重配可能等待监听线程退出，因此在 boundedElastic 上执行；该并发值不是 OAuth 或 IMAP 的外部连接上限。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitMailInspectionListenerControl
        implements MailInspectionListenerControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RabbitMailInspectionListenerControl.class);

    private final RabbitListenerEndpointRegistry registry;
    private final AdminMailInspectionJobStore jobStore;

    public RabbitMailInspectionListenerControl(
            RabbitListenerEndpointRegistry registry,
            AdminMailInspectionJobStore jobStore) {
        this.registry = Objects.requireNonNull(registry);
        this.jobStore = Objects.requireNonNull(jobStore);
    }

    @Override
    public Mono<Void> prepare(
            MailInspectionType type,
            int businessConcurrency) {
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            ListenerProgress progress = new ListenerProgress();
            return Mono.fromRunnable(() -> {
                        progress.mark(ListenerFailurePoint.LISTENER_LOOKUP);
                        SimpleMessageListenerContainer container =
                                requiredContainer(type);
                        if (container.isRunning()) {
                            progress.mark(ListenerFailurePoint.LISTENER_STOP);
                            container.stop();
                        }
                        progress.mark(
                                ListenerFailurePoint
                                        .LISTENER_SET_CONCURRENCY);
                        configureConcurrency(
                                container,
                                businessConcurrency);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then()
                    .doOnSuccess(ignored -> logSuccess(
                            "admin_mail_inspection_listener_prepared",
                            type,
                            businessConcurrency,
                            startedNanos))
                    .doOnError(exception -> logFailure(
                            type,
                            "LISTENER_PREPARE",
                            progress,
                            businessConcurrency,
                            exception,
                            startedNanos));
        });
    }

    @Override
    public Mono<Void> start(
            MailInspectionType type,
            int businessConcurrency) {
        return Mono.defer(() -> {
            long startedNanos = System.nanoTime();
            ListenerProgress progress = new ListenerProgress();
            return Mono.fromRunnable(() -> {
                        progress.mark(ListenerFailurePoint.LISTENER_LOOKUP);
                        SimpleMessageListenerContainer container =
                                requiredContainer(type);
                        progress.mark(
                                ListenerFailurePoint
                                        .LISTENER_SET_CONCURRENCY);
                        configureConcurrency(
                                container,
                                businessConcurrency);
                        if (!container.isRunning()) {
                            progress.mark(
                                    ListenerFailurePoint
                                            .LISTENER_CONTAINER_START);
                            container.start();
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then()
                    .doOnSuccess(ignored -> logSuccess(
                            "admin_mail_inspection_listener_started",
                            type,
                            businessConcurrency,
                            startedNanos))
                    .doOnError(exception -> logFailure(
                            type,
                            "LISTENER_START",
                            progress,
                            businessConcurrency,
                            exception,
                            startedNanos));
        });
    }

    @Override
    public void stop(MailInspectionType type) {
        MessageListenerContainer container = registry.getListenerContainer(
                MailInspectionRabbitNames.listenerId(type));
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Override
    public void stopAll() {
        MailInspectionRabbitNames.supportedTypes().forEach(this::stop);
    }

    /**
     * ACK 发生在异步 Mono 完成之后；定时检查只在该类型已无 RUNNING 任务时停止空闲容器，避免在回调中提前停止 ACK 通道。
     */
    @Scheduled(fixedDelay = 1000L)
    public void stopIdleContainers() {
        Set<MailInspectionType> runningTypes;
        try {
            runningTypes = jobStore.findActiveJobs().stream()
                    .filter(state -> state.status()
                            == MailInspectionJobStatus.RUNNING)
                    .map(state -> state.inspectionType())
                    .collect(java.util.stream.Collectors.toCollection(
                            () -> EnumSet.noneOf(MailInspectionType.class)));
        } catch (RuntimeException exception) {
            // 无法读取 Redis 权威活动列表时立即停止全部消费者，禁止以旧的本地容器状态继续 ACK。
            stopAll();
            throw exception;
        }
        for (MailInspectionType type :
                MailInspectionRabbitNames.supportedTypes()) {
            if (!runningTypes.contains(type)) {
                stop(type);
            }
        }
    }

    private SimpleMessageListenerContainer requiredContainer(
            MailInspectionType type) {
        MessageListenerContainer container = registry.getListenerContainer(
                MailInspectionRabbitNames.listenerId(type));
        if (!(container
                instanceof SimpleMessageListenerContainer simple)) {
            throw new IllegalStateException(
                    "mail inspection listener container unavailable");
        }
        return simple;
    }

    /**
     * 使用相同的上下界锁定任务并发，避免单值配置仅改变最低消费者数量而保留旧的动态扩容上限。
     */
    static void configureConcurrency(
            SimpleMessageListenerContainer container,
            int target) {
        if (target < 1 || target > 64) {
            throw new IllegalArgumentException(
                    "business concurrency must be between 1 and 64");
        }
        // Spring 会先清空旧上限，再按顺序设置最低值和最高值，因此升降并发都不会产生临时边界冲突。
        container.setConcurrency(target + "-" + target);
    }

    private static void logSuccess(
            String event,
            MailInspectionType type,
            int businessConcurrency,
            long startedNanos) {
        LOGGER.info(
                "event={} inspectionType={} businessConcurrency={} elapsedMs={}",
                event,
                type,
                businessConcurrency,
                elapsedMillis(startedNanos));
    }

    private static void logFailure(
            MailInspectionType type,
            String operation,
            ListenerProgress progress,
            int businessConcurrency,
            Throwable failure,
            long startedNanos) {
        // 异常消息可能包含 broker 地址或第三方内容，只输出固定阶段和异常类型以定位控制面故障。
        LOGGER.warn(
                "event={} inspectionType={} operation={} failurePoint={} "
                        + "businessConcurrency={} exceptionType={} "
                        + "rootCauseType={} failureOrigin={} elapsedMs={}",
                "admin_mail_inspection_listener_failed",
                type,
                operation,
                progress.failurePoint(),
                businessConcurrency,
                failure.getClass().getName(),
                rootCauseType(failure),
                failureOrigin(failure),
                elapsedMillis(startedNanos));
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(
                0L,
                (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String rootCauseType(Throwable failure) {
        return deepestCause(failure).getClass().getName();
    }

    private static String failureOrigin(Throwable failure) {
        StackTraceElement[] stackTrace = deepestCause(failure).getStackTrace();
        if (stackTrace.length == 0) {
            return "absent";
        }
        StackTraceElement origin = stackTrace[0];
        return origin.getClassName()
                + "#"
                + origin.getMethodName()
                + ":"
                + origin.getLineNumber();
    }

    private static Throwable deepestCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        Throwable current = failure;
        while (current.getCause() != null
                && visited.add(current)
                && !visited.contains(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private enum ListenerFailurePoint {
        LISTENER_LOOKUP,
        LISTENER_STOP,
        LISTENER_SET_CONCURRENCY,
        LISTENER_CONTAINER_START
    }

    /**
     * 保存单次监听容器控制操作最后进入的固定阶段，使故障日志无需输出第三方异常正文即可定位语句边界。
     */
    private static final class ListenerProgress {

        private ListenerFailurePoint failurePoint =
                ListenerFailurePoint.LISTENER_LOOKUP;

        void mark(ListenerFailurePoint value) {
            failurePoint = Objects.requireNonNull(value);
        }

        ListenerFailurePoint failurePoint() {
            return failurePoint;
        }
    }
}
