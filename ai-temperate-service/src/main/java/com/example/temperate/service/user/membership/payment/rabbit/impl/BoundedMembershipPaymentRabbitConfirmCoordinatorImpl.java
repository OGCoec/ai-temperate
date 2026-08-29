package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentRabbitPublishBreakdown;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitConfirmCoordinator;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该实现是来用八个发布 worker 和 256 个未确认许可并发等待 Broker Confirm，消除逐条同步发布造成的队头阻塞。
 *
 * <p>worker 只负责调用 convertAndSend，Confirm 由 CorrelationData Future 异步完成；原调用线程仍必须等待自己的
 * ACK。NACK、Return、发送异常和超时只完成对应任务，不触发业务自动重试。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class BoundedMembershipPaymentRabbitConfirmCoordinatorImpl
        implements MembershipPaymentRabbitConfirmCoordinator, InitializingBean, DisposableBean {

    private static final int WORKER_COUNT = 8;
    private static final int MAXIMUM_UNCONFIRMED = 256;
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long IDLE_POLL_MILLIS = 100L;

    private final RabbitTemplate rabbitTemplate;
    private final MembershipPaymentMetrics metrics;
    private final ExecutorService executor;
    private final Semaphore permits = new Semaphore(MAXIMUM_UNCONFIRMED, true);
    private final ArrayBlockingQueue<PublishTask> queue =
            new ArrayBlockingQueue<>(MAXIMUM_UNCONFIRMED, true);
    private final Set<PublishTask> outstanding = ConcurrentHashMap.newKeySet();
    private final List<Future<?>> workers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean forceStopping = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(WORKER_COUNT);
    private final Object completionMonitor = new Object();

    public BoundedMembershipPaymentRabbitConfirmCoordinatorImpl(
            @Qualifier("membershipPaymentRabbitTemplate") RabbitTemplate rabbitTemplate,
            MembershipPaymentMetrics metrics,
            @Qualifier("membershipPaymentRabbitPublishExecutor") ExecutorService executor) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.metrics = Objects.requireNonNull(metrics);
        this.executor = Objects.requireNonNull(executor);
    }

    /** 八个 worker 必须在 Bean 初始化阶段全部提交，防止服务以低于合同的发布并发静默运行。 */
    @Override
    public void afterPropertiesSet() {
        if (!accepting.compareAndSet(false, true)) {
            throw new IllegalStateException("Rabbit confirm coordinator was already started.");
        }
        try {
            for (int index = 0; index < WORKER_COUNT; index++) {
                workers.add(executor.submit(this::publishLoop));
            }
        } catch (RuntimeException exception) {
            stopAll(unavailable(exception));
            throw exception;
        }
    }

    @Override
    public MembershipPaymentRabbitPublishBreakdown publishAndAwait(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Duration delay) {
        String validExchange = Objects.requireNonNull(exchange);
        String validRoutingKey = Objects.requireNonNull(routingKey);
        MembershipPaymentRabbitEnvelope<?> validEnvelope = Objects.requireNonNull(envelope);
        long delayMillis = roundedUpDelayMillis(Objects.requireNonNull(delay));
        if (delayMillis < 0L || delayMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Membership payment Rabbit delay is invalid.");
        }
        if (!accepting.get()) {
            metrics.rabbitPublishRejected("not_accepting");
            throw unavailable(null);
        }
        long startedNanos = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(
                    CONFIRM_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.rabbitPublishRejected("permit_interrupted");
            throw unavailable(exception);
        }
        long permitWaitNanos = Math.max(0L, System.nanoTime() - startedNanos);
        metrics.rabbitPublishPhase("permit", acquired ? "accepted" : "timeout", permitWaitNanos);
        if (!acquired) {
            metrics.rabbitPublishRejected("permit_timeout");
            throw unavailable(null);
        }

        PublishTask task = new PublishTask(
                validExchange,
                validRoutingKey,
                validEnvelope,
                delayMillis,
                startedNanos,
                System.nanoTime(),
                new CompletableFuture<>());
        outstanding.add(task);
        metrics.rabbitPublishInflightChanged(1);
        if (!accepting.get() || !queue.offer(task)) {
            fail(task, unavailable(null));
            metrics.rabbitPublishRejected("queue_full");
            throw unavailable(null);
        }
        try {
            return task.result().get(CONFIRM_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(task, unavailable(exception));
            throw unavailable(exception);
        } catch (TimeoutException exception) {
            metrics.rabbitPublishRejected("confirm_timeout");
            fail(task, unavailable(exception));
            throw unavailable(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof MembershipPaymentException paymentException) {
                throw paymentException;
            }
            throw unavailable(cause);
        }
    }

    private void publishLoop() {
        try {
            while (!forceStopping.get() && (accepting.get() || !queue.isEmpty())) {
                PublishTask task = queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                // 调用方已超时或中断的未发布任务不能迟到发送，否则上层重试会制造不必要的重复消息。
                if (task != null && !task.result().isDone()) {
                    publish(task);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!forceStopping.get()) {
                stopAll(unavailable(exception));
            }
        } catch (RuntimeException exception) {
            stopAll(unavailable(exception));
        } finally {
            stopped.countDown();
        }
    }

    private void publish(PublishTask task) {
        long publishStartedNanos = System.nanoTime();
        metrics.rabbitPublishPhase(
                "queue", "success", publishStartedNanos - task.enqueuedNanos());
        CorrelationData correlation = new CorrelationData(task.envelope().messageId());
        try {
            rabbitTemplate.convertAndSend(
                    task.exchange(),
                    task.routingKey(),
                    task.envelope(),
                    message -> {
                        message.getMessageProperties().setDeliveryMode(
                                MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(
                                task.envelope().messageId());
                        message.getMessageProperties().setType(
                                task.envelope().eventType());
                        message.getMessageProperties().setHeader(
                                "x-delay", task.delayMillis());
                        return message;
                    },
                    correlation);
            long publishedNanos = System.nanoTime();
            metrics.rabbitPublishPhase(
                    "send", "success", publishedNanos - publishStartedNanos);
            correlation.getFuture().whenComplete((confirm, failure) -> {
                long confirmNanos = Math.max(0L, System.nanoTime() - publishedNanos);
                if (failure != null) {
                    metrics.rabbitPublishPhase("confirm", "failed", confirmNanos);
                    fail(task, unavailable(failure));
                } else if (confirm == null || !confirm.isAck()) {
                    metrics.rabbitPublishNack();
                    metrics.rabbitPublishPhase("confirm", "nack", confirmNanos);
                    fail(task, unavailable(null));
                } else if (correlation.getReturned() != null) {
                    metrics.rabbitPublishReturned();
                    metrics.rabbitPublishPhase("confirm", "returned", confirmNanos);
                    fail(task, unavailable(null));
                } else {
                    metrics.rabbitPublishPhase("confirm", "ack", confirmNanos);
                    complete(task, new MembershipPaymentRabbitPublishBreakdown(
                            Math.max(0L, publishedNanos - task.startedNanos()),
                            confirmNanos,
                            1));
                }
            });
        } catch (RuntimeException exception) {
            metrics.rabbitPublishPhase(
                    "send", "failed", System.nanoTime() - publishStartedNanos);
            fail(task, unavailable(exception));
        }
    }

    private void complete(
            PublishTask task,
            MembershipPaymentRabbitPublishBreakdown breakdown) {
        if (task.result().complete(Objects.requireNonNull(breakdown))) {
            finish(task);
        }
    }

    private void fail(PublishTask task, MembershipPaymentException failure) {
        if (task.result().completeExceptionally(Objects.requireNonNull(failure))) {
            finish(task);
        }
    }

    private void finish(PublishTask task) {
        if (outstanding.remove(task)) {
            permits.release();
            metrics.rabbitPublishInflightChanged(-1);
            synchronized (completionMonitor) {
                completionMonitor.notifyAll();
            }
        }
    }

    private void stopAll(MembershipPaymentException failure) {
        accepting.set(false);
        forceStopping.set(true);
        for (Future<?> worker : List.copyOf(workers)) {
            worker.cancel(true);
        }
        for (PublishTask task : List.copyOf(outstanding)) {
            fail(task, failure);
        }
        queue.clear();
    }

    /** 停机先停止接收并等待已发布任务确认；五秒后仍未完成的任务统一失败并释放本地容量。 */
    @Override
    public void destroy() {
        accepting.set(false);
        long deadlineNanos = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        try {
            boolean workersStopped = stopped.await(
                    remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            synchronized (completionMonitor) {
                while (workersStopped && !outstanding.isEmpty()) {
                    long remainingNanos = remainingNanos(deadlineNanos);
                    if (remainingNanos == 0L) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(completionMonitor, remainingNanos);
                }
            }
            if (!workersStopped || !outstanding.isEmpty()) {
                stopAll(unavailable(null));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stopAll(unavailable(exception));
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static long roundedUpDelayMillis(Duration delay) {
        if (delay.isNegative()) {
            return -1L;
        }
        long truncated = delay.toMillis();
        if (!delay.isZero() && delay.compareTo(Duration.ofMillis(truncated)) > 0) {
            return Math.addExact(truncated, 1L);
        }
        return truncated;
    }

    private static MembershipPaymentException unavailable(Throwable cause) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                "Membership payment Rabbit publish was not confirmed.",
                cause);
    }

    /** 该任务只携带一个消息的 Confirm 状态，任何失败都不能连带完成同批其他调用方。 */
    private record PublishTask(
            String exchange,
            String routingKey,
            MembershipPaymentRabbitEnvelope<?> envelope,
            long delayMillis,
            long startedNanos,
            long enqueuedNanos,
            CompletableFuture<MembershipPaymentRabbitPublishBreakdown> result) {
    }
}
