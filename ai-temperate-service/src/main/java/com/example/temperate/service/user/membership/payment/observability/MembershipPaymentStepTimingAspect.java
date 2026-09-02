package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentClient;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitSender;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.example.temperate.service.user.membership.payment.store.SimulatedPaymentProviderResultStore;
import java.util.Map;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 该切面是来在会员支付总流程内部累计Redis、数据库、Marker、BAR和Rabbit Confirm耗时，不单独输出逐步骤日志。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = {"enabled", "observability.enabled"},
        havingValue = "true")
public final class MembershipPaymentStepTimingAspect {

    private final MembershipPaymentTimingRecorder recorder;

    public MembershipPaymentStepTimingAspect(
            MembershipPaymentTimingRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    /**
     * 只有当前线程已经存在会员支付总流程时才计时，普通Mapper和Redis调用不会产生额外指标或上下文。
     */
    @Around(
            "execution(* com.example.temperate.mapper.user.membership..*.*(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.store..*.*(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.provider.bar."
                    + "BarPaymentClient.*(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.provider.bar."
                    + "BarPaymentSignatureService.*(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.rabbit."
                    + "MembershipPaymentRabbitSender.send(..))")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!recorder.active()) {
            return joinPoint.proceed();
        }
        MembershipPaymentTimingStep step = step(joinPoint);
        String method = joinPoint.getSignature().getName();
        long startedNanos = System.nanoTime();
        boolean succeeded = false;
        try {
            Object result = joinPoint.proceed();
            succeeded = true;
            recorder.capture(result);
            captureOutcome(joinPoint, method, result);
            return result;
        } finally {
            recorder.recordStep(
                    step,
                    Math.max(0L, System.nanoTime() - startedNanos),
                    succeeded);
        }
    }

    private void captureOutcome(
            ProceedingJoinPoint joinPoint,
            String method,
            Object result) {
        Object target = joinPoint.getTarget();
        if (target instanceof MembershipOrderSnapshotStore) {
            if ("callbackInProgress".equals(method) && result instanceof Boolean present) {
                recorder.markMarker(present);
            }
            if (result instanceof MembershipOrderTransitionResult transition) {
                recorder.markTransition(method, transition);
            }
            if (result instanceof Map<?, ?> transitions) {
                recorder.markTransitions(method, transitions);
            }
        }
        if (target instanceof MembershipPaymentRabbitSender) {
            for (Object argument : joinPoint.getArgs()) {
                if (argument instanceof MembershipPaymentRabbitEnvelope<?> envelope) {
                    recorder.markNextMessage(envelope);
                }
            }
        }
    }

    private static MembershipPaymentTimingStep step(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        String method = joinPoint.getSignature().getName();
        String declaringType = joinPoint.getSignature().getDeclaringTypeName();
        if (declaringType.startsWith("com.example.temperate.mapper.user.membership.")) {
            return MembershipPaymentTimingStep.DATABASE_CALL;
        }
        if (target instanceof MembershipPaymentRabbitSender) {
            return MembershipPaymentTimingStep.RABBIT_PUBLISH_CONFIRM;
        }
        if (target instanceof BarPaymentSignatureService) {
            return MembershipPaymentTimingStep.BAR_SIGNATURE;
        }
        if (target instanceof BarPaymentClient) {
            return switch (method) {
                case "createCheckout" -> MembershipPaymentTimingStep.BAR_CREATE;
                case "queryPayment" -> MembershipPaymentTimingStep.BAR_QUERY;
                case "closePayment" -> MembershipPaymentTimingStep.BAR_CLOSE;
                case "refundPayment" -> MembershipPaymentTimingStep.BAR_REFUND;
                default -> MembershipPaymentTimingStep.BAR_QUERY;
            };
        }
        if (target instanceof PaymentCallbackQueue) {
            return switch (method) {
                case "enqueue" -> MembershipPaymentTimingStep.CALLBACK_ENQUEUE;
                case "claim" -> MembershipPaymentTimingStep.CALLBACK_CLAIM;
                case "findAll" -> MembershipPaymentTimingStep.CALLBACK_READ;
                case "requeue", "recoverTimedOut", "ensureReady" ->
                        MembershipPaymentTimingStep.CALLBACK_REQUEUE;
                case "complete" -> MembershipPaymentTimingStep.CALLBACK_COMPLETE;
                default -> MembershipPaymentTimingStep.OTHER_REDIS;
            };
        }
        if (target instanceof SimulatedPaymentProviderResultStore) {
            return ("put".equals(method) || "initializeUnpaid".equals(method))
                    ? MembershipPaymentTimingStep.REDIS_PROVIDER_WRITE
                    : MembershipPaymentTimingStep.OTHER_REDIS;
        }
        if (target instanceof MembershipOrderSnapshotWriteCoordinator) {
            // Redis 命令在专用 worker 执行；HTTP 线程等待协调器 Future 的完整时间才是用户实际观察到的写入耗时。
            return MembershipPaymentTimingStep.REDIS_ORDER_WRITE;
        }
        if (target instanceof MembershipOrderSnapshotStore) {
            return switch (method) {
                case "find", "findAll", "findRealtimeGuard" ->
                        MembershipPaymentTimingStep.REDIS_ORDER_READ;
                case "put", "putAll", "putAndGet", "putAndGetAll", "writeAll",
                        "patchProviderTradeNo" ->
                        MembershipPaymentTimingStep.REDIS_ORDER_WRITE;
                case "callbackInProgress" -> MembershipPaymentTimingStep.MARKER_READ;
                case "markPaid", "markPaidAll", "cancel", "startClosing", "finalizeClosing",
                        "supersedeForReplacement" ->
                        MembershipPaymentTimingStep.REDIS_STATE_TRANSITION;
                default -> MembershipPaymentTimingStep.OTHER_REDIS;
            };
        }
        return MembershipPaymentTimingStep.OTHER_REDIS;
    }
}
