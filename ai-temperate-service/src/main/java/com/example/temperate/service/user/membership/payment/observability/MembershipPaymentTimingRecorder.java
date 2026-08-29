package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该记录器是来维护会员支付同步调用栈的计时上下文、输出单条SLF4J汇总日志并提交低基数Micrometer指标。
 *
 * <p>普通环境只输出不可逆订单摘要；只有受控压测配置可以输出公共Base64URL订单号。记录器始终禁止异常消息、
 * 请求体、原始数据库ID和Redis Key，任何诊断失败也不得替换原业务结果。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentTimingRecorder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipPaymentTimingRecorder.class);
    private static final Logger TIMING_LOGGER =
            LoggerFactory.getLogger("membership.payment.state.timing");

    private final MembershipPaymentMetrics metrics;
    private final MembershipPaymentObservabilityProperties properties;
    private final Clock clock;
    private final ThreadLocal<Deque<MembershipPaymentTimingContext>> contexts =
            ThreadLocal.withInitial(ArrayDeque::new);

    public MembershipPaymentTimingRecorder(
            MembershipPaymentMetrics metrics,
            MembershipPaymentObservabilityProperties properties,
            Clock clock) {
        this.metrics = Objects.requireNonNull(metrics);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public Session start(
            MembershipPaymentOperation operation,
            Object[] arguments) {
        if (!properties.enabled()) {
            return Session.inactive();
        }
        MembershipPaymentTimingContext context = null;
        try {
            context = new MembershipPaymentTimingContext(
                    Objects.requireNonNull(operation), clock.instant());
            context.captureLoggingContext(MDC.get("traceId"), MDC.get("messageId"));
            context.capture(arguments);
            contexts.get().push(context);
            metrics.operationStarted(operation);
            return new Session(context);
        } catch (RuntimeException diagnosticsFailure) {
            if (context != null) {
                remove(context);
            }
            diagnosticsFailedSafely(diagnosticsFailure);
            return Session.inactive();
        }
    }

    public boolean active() {
        try {
            Deque<MembershipPaymentTimingContext> stack = contexts.get();
            boolean active = !stack.isEmpty();
            if (!active) {
                contexts.remove();
            }
            return active;
        } catch (RuntimeException diagnosticsFailure) {
            contexts.remove();
            diagnosticsFailedSafely(diagnosticsFailure);
            return false;
        }
    }

    public void capture(Object value) {
        safely(() -> current().ifPresent(context -> context.capture(value)));
    }

    public void recordStep(
            MembershipPaymentTimingStep step,
            long elapsedNanos,
            boolean succeeded) {
        safely(() -> current().ifPresent(context -> {
            context.recordStep(step, elapsedNanos);
            metrics.stepCompleted(step, succeeded, elapsedNanos);
        }));
    }

    public void recordRedisWriteBreakdown(
            MembershipPaymentRedisWriteBreakdown breakdown) {
        safely(() -> current().ifPresent(
                context -> context.recordRedisWriteBreakdown(breakdown)));
    }

    public void recordRabbitPublishBreakdown(
            MembershipPaymentRabbitPublishBreakdown breakdown) {
        safely(() -> current().ifPresent(
                context -> context.recordRabbitPublishBreakdown(breakdown)));
    }

    public void recordDatabaseTransaction(long elapsedNanos, boolean succeeded) {
        safely(() -> current().ifPresent(context -> {
            context.recordDatabaseTransaction(elapsedNanos);
            metrics.databaseTransaction(context.operation(), succeeded, elapsedNanos);
        }));
    }

    public void markMarker(boolean present) {
        safely(() -> current().ifPresent(context -> context.markMarker(present)));
    }

    public void markTransition(
            String methodName,
            MembershipOrderTransitionResult result) {
        safely(() -> current().ifPresent(context -> {
            context.markTransition(methodName, result);
            metrics.transition(context.transition(), context.transitionOutcome());
        }));
    }

    public void markTransitions(
            String methodName,
            Map<?, ?> results) {
        safely(() -> current().ifPresent(context -> {
            for (Object value : results.values()) {
                if (value instanceof MembershipOrderTransitionResult result) {
                    context.markTransition(methodName, result);
                    metrics.transition(context.transition(), context.transitionOutcome());
                }
            }
        }));
    }

    public void markNextMessage(MembershipPaymentRabbitEnvelope<?> envelope) {
        safely(() -> current().ifPresent(context -> context.captureNextMessage(envelope)));
    }

    public void markDecision(String decision) {
        safely(() -> current().ifPresent(context -> context.markDecision(decision)));
    }

    public void markRabbitOutcome(String action, long priorDeliveries) {
        safely(() -> current().ifPresent(
                context -> context.markRabbitOutcome(action, priorDeliveries)));
    }

    public void markFailure(Throwable failure) {
        safely(() -> current().ifPresent(context -> context.markFailure(failure)));
    }

    public void finish(Session session, Object result, Throwable failure) {
        if (session == null || session.closed || session.context == null) {
            return;
        }
        session.closed = true;
        MembershipPaymentTimingContext context = session.context;
        try {
            context.capture(result);
            long elapsedNanos = context.elapsedNanos();
            Instant completedAt = clock.instant();
            String outcome = outcome(context, failure);
            metrics.operationCompleted(context.operation(), outcome, elapsedNanos);
            metrics.rabbitDeliveryOverdue(
                    context.flow(), context.stageIndex(), context.deliveryOverdueMs());
            if (shouldLog(context, elapsedNanos, failure)) {
                log(context, outcome, elapsedNanos, completedAt, failure);
            }
        } catch (RuntimeException diagnosticsFailure) {
            diagnosticsFailedSafely(diagnosticsFailure);
        } finally {
            remove(context);
        }
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException diagnosticsFailure) {
            diagnosticsFailedSafely(diagnosticsFailure);
        }
    }

    private static void diagnosticsFailedSafely(RuntimeException diagnosticsFailure) {
        LOGGER.debug(
                "Membership payment timing diagnostics failed safely; exceptionClass={}",
                diagnosticsFailure.getClass().getSimpleName());
    }

    private java.util.Optional<MembershipPaymentTimingContext> current() {
        Deque<MembershipPaymentTimingContext> stack = contexts.get();
        if (stack.isEmpty()) {
            contexts.remove();
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(stack.peek());
    }

    private void remove(MembershipPaymentTimingContext context) {
        Deque<MembershipPaymentTimingContext> stack = contexts.get();
        if (!stack.isEmpty() && stack.peek() == context) {
            stack.pop();
        } else {
            stack.remove(context);
        }
        if (stack.isEmpty()) {
            contexts.remove();
        }
    }

    private boolean shouldLog(
            MembershipPaymentTimingContext context,
            long elapsedNanos,
            Throwable failure) {
        if (!properties.enabled()) {
            return false;
        }
        if (failure != null
                || "NACK".equals(context.ackAction())
                || context.deliveryCount() > 0L
                || elapsedNanos >= properties.slowThreshold().toNanos()
                || properties.detailLogEnabled()) {
            return true;
        }
        // 强制操作与慢请求、失败、NACK 规则取并集，不能让白名单意外吞掉其他链路的重要异常证据。
        if (properties.forceLogOperations().contains(context.operation())) {
            return true;
        }
        if (properties.sampleRate() <= 0D) {
            return false;
        }
        long bucket = Integer.toUnsignedLong(context.traceId().hashCode());
        double ratio = bucket / (double) (1L << 32);
        return ratio < properties.sampleRate();
    }

    private static String outcome(
            MembershipPaymentTimingContext context,
            Throwable failure) {
        if (failure != null) {
            return "FAILED";
        }
        return switch (context.ackAction()) {
            case "ACK" -> "ACKED";
            case "NACK" -> "NACKED";
            default -> "SUCCESS";
        };
    }

    private void log(
            MembershipPaymentTimingContext context,
            String outcome,
            long elapsedNanos,
            Instant completedAt,
            Throwable failure) {
        String errorClass = failure == null
                ? context.exceptionClass()
                : safeExceptionClass(failure);
        long applicationNanos = Math.max(
                0L, elapsedNanos - Math.min(elapsedNanos, context.primaryStepNanos()));
        boolean diagnostic = failure != null
                || "NACK".equals(context.ackAction())
                || context.deliveryCount() > 0L
                || elapsedNanos >= properties.slowThreshold().toNanos()
                || properties.detailLogEnabled();

        // v2 使用短键并省略无意义的零值；两个 HTTP 主操作仍逐次输出，因此不会牺牲全量百分位口径。
        StringBuilder event = new StringBuilder(288);
        event.append("event=membership_payment_operation_completed v=2");
        append(event, "r", properties.runId());
        append(event, "op", context.operation().name());
        if (properties.includePublicOrderId()) {
            appendOptional(event, "oid", context.orderIdB64());
        }
        append(event, "out", outcome);
        append(event, "end", completedAt.toEpochMilli());
        append(event, "t", millis(elapsedNanos));
        if (applicationNanos > 0L) {
            append(event, "app", millis(applicationNanos));
        }
        appendStepIfPresent(event, "ro", context, MembershipPaymentTimingStep.REDIS_ORDER_READ);
        appendStepIfPresent(event, "row", context, MembershipPaymentTimingStep.REDIS_ORDER_WRITE);
        appendNanosIfPresent(event, "rwp", context.redisWritePermitNanos());
        appendNanosIfPresent(event, "rwq", context.redisWriteQueueNanos());
        appendNanosIfPresent(event, "rwb", context.redisWriteBatchNanos());
        appendNanosIfPresent(event, "rwe", context.redisWriteExecutionNanos());
        appendNanosIfPresent(event, "rwd", context.redisWriteDispatchNanos());
        appendIfPositive(event, "rwsz", context.redisWriteBatchSize());
        appendIfNonNegative(event, "rwl", context.redisWriteLane());
        appendStepIfPresent(event, "rpw", context, MembershipPaymentTimingStep.REDIS_PROVIDER_WRITE);
        appendStepIfPresent(event, "rt", context, MembershipPaymentTimingStep.REDIS_STATE_TRANSITION);
        appendStepIfPresent(event, "or", context, MembershipPaymentTimingStep.OTHER_REDIS);
        appendStepIfPresent(event, "rpc", context, MembershipPaymentTimingStep.RABBIT_PUBLISH_CONFIRM);
        appendNanosIfPresent(event, "rps", context.rabbitPublishSubmitNanos());
        appendNanosIfPresent(event, "rcw", context.rabbitConfirmWaitNanos());
        appendIfPositive(event, "rpsz", context.rabbitSubmissionSize());
        appendStepIfPresent(event, "mk", context, MembershipPaymentTimingStep.MARKER_READ);
        appendStepIfPresent(event, "db", context, MembershipPaymentTimingStep.DATABASE_CALL);
        appendNanosIfPresent(event, "dbt", context.databaseTransactionNanos());
        appendStepIfPresent(event, "ack", context, MembershipPaymentTimingStep.RABBIT_ACK);
        appendOptional(event, "aa", context.ackAction());
        appendStepIfPresent(event, "br", context, MembershipPaymentTimingStep.BAR_REFUND);
        appendOptional(event, "err", errorClass);

        if (diagnostic) {
            // 慢请求、失败、NACK 和重投附带关联字段，快速 HTTP 主样本不承担这些高体积诊断字段。
            appendOptional(event, "tr", context.traceId());
            appendOptional(event, "mid", context.messageId());
            appendOptional(event, "fl", context.flow());
            appendIfNonNegative(event, "si", context.stageIndex());
            append(event, "dc", context.deliveryCount());
            appendIfNonNegative(event, "qa", context.queueAgeMs());
            appendIfNonNegative(event, "sd", context.scheduledDelayMs());
            appendIfNonNegative(event, "do", context.deliveryOverdueMs());
            appendStepIfPresent(event, "bq", context, MembershipPaymentTimingStep.BAR_QUERY);
            appendStepIfPresent(event, "bc", context, MembershipPaymentTimingStep.BAR_CLOSE);
        }
        if (failure != null || "NACK".equals(context.ackAction())) {
            TIMING_LOGGER.warn("{}", event);
        } else {
            TIMING_LOGGER.info("{}", event);
        }
    }

    private static void appendStepIfPresent(
            StringBuilder event,
            String key,
            MembershipPaymentTimingContext context,
            MembershipPaymentTimingStep step) {
        long nanos = context.stepNanos(step);
        if (nanos > 0L) {
            append(event, key, millis(nanos));
        }
    }

    private static void appendNanosIfPresent(
            StringBuilder event,
            String key,
            long nanos) {
        if (nanos > 0L) {
            append(event, key, millis(nanos));
        }
    }

    private static void appendOptional(StringBuilder event, String key, String value) {
        if (value != null
                && !value.isBlank()
                && !MembershipPaymentTimingContext.UNAVAILABLE.equals(value)
                && !"none".equals(value)) {
            append(event, key, value);
        }
    }

    private static void appendIfNonNegative(StringBuilder event, String key, long value) {
        if (value >= 0L) {
            append(event, key, value);
        }
    }

    private static void appendIfPositive(StringBuilder event, String key, long value) {
        if (value > 0L) {
            append(event, key, value);
        }
    }

    private static void append(StringBuilder event, String key, Object value) {
        event.append(' ').append(key).append('=').append(value);
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000D);
    }

    private static String safeExceptionClass(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.matches("^[A-Za-z0-9_$]{1,128}$")
                ? simpleName
                : "unavailable";
    }

    /**
     * 该会话是来绑定一次AOP入口和对应上下文，重复关闭时保持幂等并避免错误弹出其他消息的计时状态。
     */
    public static final class Session {

        private final MembershipPaymentTimingContext context;
        private boolean closed;

        private Session(MembershipPaymentTimingContext context) {
            this.context = context;
        }

        private static Session inactive() {
            return new Session(null);
        }
    }
}
