package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.amqp.core.Message;

/**
 * 该上下文是来聚合一次会员支付入口内的固定分段耗时和安全诊断元数据，并在异步线程复用前由Recorder彻底清理。
 */
final class MembershipPaymentTimingContext {

    static final String UNAVAILABLE = "unavailable";
    static final String NONE = "none";
    private static final HybridBase64UrlCodec ORDER_ID_CODEC = new HybridBase64UrlCodec();

    private final MembershipPaymentOperation operation;
    private final long startedNanos;
    private final Instant startedAt;
    private final long[] stepNanos = new long[MembershipPaymentTimingStep.values().length];
    private final int[] stepCounts = new int[MembershipPaymentTimingStep.values().length];

    private String traceId = UNAVAILABLE;
    private String messageId = UNAVAILABLE;
    private String orderIdB64 = UNAVAILABLE;
    private String orderRef = UNAVAILABLE;
    private String flow = NONE;
    private int stageIndex = -1;
    private int terminalRetryCount = -1;
    private long deliveryCount;
    private long queueAgeMs = -1L;
    private long scheduledDelayMs = -1L;
    private long deliveryOverdueMs = -1L;
    private String ackAction = NONE;
    private String exceptionClass = NONE;
    private String decision = NONE;
    private String transition = NONE;
    private String transitionOutcome = NONE;
    private int transitionCount;
    private int transitionAppliedCount;
    private String providerStatus = NONE;
    private String nextFlow = NONE;
    private int nextStageIndex = -1;
    private String initialStatus = NONE;
    private String currentStatus = NONE;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime closingDeadlineAt;
    private long redisWritePermitNanos;
    private long redisWriteQueueNanos;
    private long redisWriteBatchNanos;
    private long redisWriteExecutionNanos;
    private long redisWriteDispatchNanos;
    private int redisWriteBatchSize;
    private int redisWriteLane = -1;
    private long rabbitPublishSubmitNanos;
    private long rabbitConfirmWaitNanos;
    private int rabbitSubmissionSize;
    private long databaseTransactionNanos;

    MembershipPaymentTimingContext(
            MembershipPaymentOperation operation,
            Instant startedAt) {
        this.operation = operation;
        this.startedAt = startedAt;
        this.startedNanos = System.nanoTime();
    }

    void capture(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Object[] values) {
            for (Object item : values) {
                capture(item);
            }
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(this::capture);
            return;
        }
        if (value instanceof MembershipPaymentRabbitEnvelope<?> envelope) {
            traceId = safeText(envelope.traceId(), traceId);
            messageId = safeText(envelope.messageId(), messageId);
            capture(envelope.payload());
            if (envelope.occurredAt() != null) {
                queueAgeMs = nonNegativeMillis(envelope.occurredAt().toInstant(), startedAt);
            }
            return;
        }
        if (value instanceof MembershipPaymentCheckMessage message) {
            flow = "PENDING";
            stageIndex = message.stageIndex();
            setOrderIdentity(message.orderId());
            return;
        }
        if (value instanceof MembershipClosingCheckMessage message) {
            flow = "CLOSING";
            stageIndex = message.stageIndex();
            terminalRetryCount = message.terminalRetryCount();
            setOrderIdentity(message.orderId());
            return;
        }
        if (value instanceof Message message) {
            captureRabbitProperties(message);
            return;
        }
        if (value instanceof MembershipOrderResult result) {
            capture(result.snapshot());
            return;
        }
        if (value instanceof MembershipPaymentAttemptResult result) {
            capture(result.snapshot());
            providerStatus = result.provider().name();
            return;
        }
        if (value instanceof MembershipOrderSnapshot snapshot) {
            setOrderIdentity(snapshot.orderId());
            captureStatus(snapshot.status().name());
            createdAt = snapshot.createdAt();
            expiresAt = snapshot.expiresAt();
            closingDeadlineAt = snapshot.closingDeadlineAt();
            return;
        }
        if (value instanceof MembershipOrder order) {
            setOrderIdentity(order.getId());
            captureStatus(order.getStatus() == null ? NONE : order.getStatus().name());
            createdAt = order.getCreatedAt();
            expiresAt = order.getExpiresAt();
            closingDeadlineAt = order.getClosingDeadlineAt();
            return;
        }
        if (value instanceof BarPaymentCallbackCommand command) {
            setOrderIdentity(command.outTradeNo());
            return;
        }
        if (value instanceof SimulatedLiuhaoCallbackCommand command) {
            setOrderIdentity(command.outTradeNo());
            return;
        }
        if (value instanceof PaymentQueryResult result) {
            providerStatus = result.status().name();
            setOrderIdentity(result.orderId());
            return;
        }
        if (value instanceof PaymentCloseResult result) {
            providerStatus = result.status().name();
            return;
        }
        if (value instanceof PaymentRefundResult result) {
            providerStatus = result.status().name();
            return;
        }
        if (value instanceof MembershipOrderTransitionResult result) {
            transitionOutcome = result.outcome().name();
            if (result.status() != null) {
                captureStatus(result.status().name());
            }
        }
    }

    void captureNextMessage(MembershipPaymentRabbitEnvelope<?> envelope) {
        if (envelope == null || envelope.payload() == null) {
            return;
        }
        if (envelope.payload() instanceof MembershipPaymentCheckMessage message) {
            nextFlow = "PENDING";
            nextStageIndex = message.stageIndex();
        } else if (envelope.payload() instanceof MembershipClosingCheckMessage message) {
            nextFlow = "CLOSING";
            nextStageIndex = message.stageIndex();
        }
    }

    void captureLoggingContext(String currentTraceId, String currentMessageId) {
        traceId = safeText(currentTraceId, traceId);
        messageId = safeText(currentMessageId, messageId);
    }

    void recordStep(MembershipPaymentTimingStep step, long elapsedNanos) {
        if (elapsedNanos < 0L) {
            return;
        }
        int index = step.ordinal();
        long current = stepNanos[index];
        stepNanos[index] = Long.MAX_VALUE - current < elapsedNanos
                ? Long.MAX_VALUE
                : current + elapsedNanos;
        if (stepCounts[index] < Integer.MAX_VALUE) {
            stepCounts[index]++;
        }
    }

    void recordRedisWriteBreakdown(MembershipPaymentRedisWriteBreakdown breakdown) {
        if (breakdown == null) {
            return;
        }
        redisWritePermitNanos = saturatingAdd(
                redisWritePermitNanos, breakdown.permitWaitNanos());
        redisWriteQueueNanos = saturatingAdd(
                redisWriteQueueNanos, breakdown.queueWaitNanos());
        redisWriteBatchNanos = saturatingAdd(
                redisWriteBatchNanos, breakdown.batchWaitNanos());
        redisWriteExecutionNanos = saturatingAdd(
                redisWriteExecutionNanos, breakdown.executionNanos());
        redisWriteDispatchNanos = saturatingAdd(
                redisWriteDispatchNanos, breakdown.dispatchNanos());
        redisWriteBatchSize = breakdown.batchSize();
        redisWriteLane = breakdown.lane();
    }

    void recordRabbitPublishBreakdown(MembershipPaymentRabbitPublishBreakdown breakdown) {
        if (breakdown == null) {
            return;
        }
        rabbitPublishSubmitNanos = saturatingAdd(
                rabbitPublishSubmitNanos, breakdown.submitNanos());
        rabbitConfirmWaitNanos = saturatingAdd(
                rabbitConfirmWaitNanos, breakdown.confirmWaitNanos());
        rabbitSubmissionSize = Math.addExact(
                rabbitSubmissionSize, breakdown.submissionSize());
    }

    void recordDatabaseTransaction(long elapsedNanos) {
        if (elapsedNanos >= 0L) {
            databaseTransactionNanos = saturatingAdd(
                    databaseTransactionNanos, elapsedNanos);
        }
    }

    void markMarker(boolean present) {
        if (present) {
            decision = "MARKER_SHORT_CIRCUIT";
        }
    }

    void markTransition(String name, MembershipOrderTransitionResult result) {
        transition = switch (name) {
            case "startClosing" -> "PENDING_PAYMENT_TO_CLOSING";
            case "finalizeClosing" -> "CLOSING_TO_CLOSED";
            case "markPaid", "markPaidAll" -> "ORDER_TO_PAID";
            case "cancel" -> "PENDING_PAYMENT_TO_CANCELLED";
            default -> name == null || name.isBlank() ? NONE : name;
        };
        capture(result);
        if (result != null && transitionCount < Integer.MAX_VALUE) {
            transitionCount++;
            if (result.applied() && transitionAppliedCount < Integer.MAX_VALUE) {
                transitionAppliedCount++;
            }
        }
    }

    void markRabbitOutcome(String action, long priorDeliveries) {
        if ("ACK".equals(action) || "NACK".equals(action)) {
            ackAction = action;
        }
        deliveryCount = Math.max(deliveryCount, Math.max(0L, priorDeliveries));
    }

    void markFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        String simpleName = failure.getClass().getSimpleName();
        exceptionClass = simpleName.matches("^[A-Za-z0-9_$]{1,128}$")
                ? simpleName
                : UNAVAILABLE;
    }

    void markDecision(String value) {
        if (value != null && !value.isBlank()) {
            decision = value;
        }
    }

    long elapsedNanos() {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    long stepNanos(MembershipPaymentTimingStep step) {
        return stepNanos[step.ordinal()];
    }

    int stepCount(MembershipPaymentTimingStep step) {
        return stepCounts[step.ordinal()];
    }

    long primaryStepNanos() {
        long total = 0L;
        for (MembershipPaymentTimingStep step : MembershipPaymentTimingStep.values()) {
            // BAR总调用已经包含内部签名CPU耗时，计算未归因时间时必须排除签名分段，避免重复扣减。
            if (step == MembershipPaymentTimingStep.BAR_SIGNATURE) {
                continue;
            }
            long value = stepNanos(step);
            total = Long.MAX_VALUE - total < value ? Long.MAX_VALUE : total + value;
        }
        return total;
    }

    long lifecycleAgeMs(Instant completedAt) {
        return createdAt == null ? -1L : nonNegativeMillis(createdAt.toInstant(), completedAt);
    }

    long boundaryLagMs(Instant completedAt) {
        OffsetDateTime boundary = switch (transition) {
            case "PENDING_PAYMENT_TO_CLOSING" -> expiresAt;
            case "CLOSING_TO_CLOSED" -> closingDeadlineAt;
            default -> null;
        };
        return boundary == null
                ? Long.MIN_VALUE
                : Duration.between(boundary.toInstant(), completedAt).toMillis();
    }

    MembershipPaymentOperation operation() {
        return operation;
    }

    String traceId() {
        return traceId;
    }

    String messageId() {
        return messageId;
    }

    String orderRef() {
        return orderRef;
    }

    String orderIdB64() {
        return orderIdB64;
    }

    String flow() {
        return flow;
    }

    int stageIndex() {
        return stageIndex;
    }

    int terminalRetryCount() {
        return terminalRetryCount;
    }

    long deliveryCount() {
        return deliveryCount;
    }

    long queueAgeMs() {
        return queueAgeMs;
    }

    long scheduledDelayMs() {
        return scheduledDelayMs;
    }

    long deliveryOverdueMs() {
        return deliveryOverdueMs;
    }

    String ackAction() {
        return ackAction;
    }

    String exceptionClass() {
        return exceptionClass;
    }

    String decision() {
        return decision;
    }

    String transition() {
        return transition;
    }

    String transitionOutcome() {
        return transitionOutcome;
    }

    int transitionCount() {
        return transitionCount;
    }

    int transitionAppliedCount() {
        return transitionAppliedCount;
    }

    String providerStatus() {
        return providerStatus;
    }

    String nextFlow() {
        return nextFlow;
    }

    int nextStageIndex() {
        return nextStageIndex;
    }

    String currentStatus() {
        return currentStatus;
    }

    String initialStatus() {
        return initialStatus;
    }

    long redisWritePermitNanos() {
        return redisWritePermitNanos;
    }

    long redisWriteQueueNanos() {
        return redisWriteQueueNanos;
    }

    long redisWriteBatchNanos() {
        return redisWriteBatchNanos;
    }

    long redisWriteExecutionNanos() {
        return redisWriteExecutionNanos;
    }

    long redisWriteDispatchNanos() {
        return redisWriteDispatchNanos;
    }

    int redisWriteBatchSize() {
        return redisWriteBatchSize;
    }

    int redisWriteLane() {
        return redisWriteLane;
    }

    long rabbitPublishSubmitNanos() {
        return rabbitPublishSubmitNanos;
    }

    long rabbitConfirmWaitNanos() {
        return rabbitConfirmWaitNanos;
    }

    int rabbitSubmissionSize() {
        return rabbitSubmissionSize;
    }

    long databaseTransactionNanos() {
        return databaseTransactionNanos;
    }

    private void captureRabbitProperties(Message message) {
        Object delay = message.getMessageProperties().getHeader("x-delay");
        if (delay instanceof Number number) {
            long raw = number.longValue();
            if (raw != Long.MIN_VALUE) {
                scheduledDelayMs = Math.abs(raw);
                if (queueAgeMs >= 0L) {
                    deliveryOverdueMs = Math.max(0L, queueAgeMs - scheduledDelayMs);
                }
            }
        }
        Object count = message.getMessageProperties().getHeader("x-delivery-count");
        if (count instanceof Number number) {
            deliveryCount = Math.max(0L, number.longValue());
        }
    }

    private void setOrderIdentity(String publicOrderId) {
        try {
            byte[] internalOrderId = ORDER_ID_CODEC.decode(publicOrderId);
            orderIdB64 = ORDER_ID_CODEC.encode(internalOrderId);
            orderRef = MembershipPaymentDiagnosticId.orderRef(internalOrderId);
        } catch (RuntimeException ignored) {
            // 非法外部ID仍由业务校验负责拒绝；诊断旁路必须保持不可用而不能改变原始失败语义。
        }
    }

    private void setOrderIdentity(byte[] internalOrderId) {
        try {
            orderIdB64 = ORDER_ID_CODEC.encode(internalOrderId);
            orderRef = MembershipPaymentDiagnosticId.orderRef(internalOrderId);
        } catch (RuntimeException ignored) {
            // Mapper异常数据仍由原业务边界处理，观测代码不能替换原异常或泄露原始字节。
        }
    }

    private void captureStatus(String status) {
        if (status == null || status.isBlank() || NONE.equals(status)) {
            return;
        }
        if (NONE.equals(initialStatus)) {
            initialStatus = status;
        }
        currentStatus = status;
    }

    private static String safeText(String value, String fallback) {
        return value != null && value.matches("^[A-Za-z0-9_-]{1,128}$")
                ? value
                : fallback;
    }

    private static long nonNegativeMillis(Instant start, Instant end) {
        try {
            return Math.max(0L, Duration.between(start, end).toMillis());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
