package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackReceiveService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 该切面是来包围会员订单、支付尝试、支付回调和回调批处理接口，使每次同步入口只产生一个总耗时上下文。
 *
 * <p>final且无接口的Rabbit监听器无法使用项目固定的JDK代理，因此由监听器显式开启同一种Recorder会话。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = {"enabled", "observability.enabled"},
        havingValue = "true")
public final class MembershipPaymentOperationTimingAspect {

    private final MembershipPaymentTimingRecorder recorder;

    public MembershipPaymentOperationTimingAspect(
            MembershipPaymentTimingRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    /**
     * 在同步业务入口外层计时，原始返回值、异常传播和既有事务边界均保持不变。
     */
    @Around(
            "execution(* com.example.temperate.service.user.membership.payment.order."
                    + "MembershipOrderService.create(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.order."
                    + "MembershipOrderService.getOwned(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.order."
                    + "MembershipOrderService.cancel(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.order."
                    + "MembershipPaymentAttemptService.start(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.callback."
                    + "BarPaymentCallbackService.receive(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.callback."
                    + "PaymentCallbackReceiveService.receive(..)) || "
                    + "execution(* com.example.temperate.service.user.membership.payment.callback."
                    + "PaymentCallbackBatchService.flushOneRun(..))")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        MembershipPaymentOperation operation = operation(joinPoint);
        MembershipPaymentTimingRecorder.Session session =
                recorder.start(operation, joinPoint.getArgs());
        Object result = null;
        Throwable failure = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            recorder.finish(session, result, failure);
        }
    }

    private static MembershipPaymentOperation operation(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        String method = joinPoint.getSignature().getName();
        if (target instanceof MembershipOrderService) {
            return switch (method) {
                case "create" -> MembershipPaymentOperation.ORDER_CREATE;
                case "getOwned" -> MembershipPaymentOperation.ORDER_GET;
                case "cancel" -> MembershipPaymentOperation.ORDER_CANCEL;
                default -> throw unsupported(joinPoint);
            };
        }
        if (target instanceof MembershipPaymentAttemptService) {
            return MembershipPaymentOperation.PAYMENT_ATTEMPT;
        }
        if (target instanceof BarPaymentCallbackService) {
            return MembershipPaymentOperation.BAR_CALLBACK_RECEIVE;
        }
        if (target instanceof PaymentCallbackReceiveService) {
            return MembershipPaymentOperation.SIMULATED_CALLBACK_RECEIVE;
        }
        if (target instanceof PaymentCallbackBatchService) {
            return MembershipPaymentOperation.CALLBACK_WORKER_BATCH;
        }
        throw unsupported(joinPoint);
    }

    private static IllegalStateException unsupported(ProceedingJoinPoint joinPoint) {
        return new IllegalStateException(
                "Unsupported membership payment timing boundary: "
                        + joinPoint.getSignature().toShortString());
    }
}
