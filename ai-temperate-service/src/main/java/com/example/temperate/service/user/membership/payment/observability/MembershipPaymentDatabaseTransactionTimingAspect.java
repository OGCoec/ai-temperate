package com.example.temperate.service.user.membership.payment.observability;

import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 该切面是来从 Spring 事务拦截器外层计量订单创建本地事务，使耗时包含连接获取、事务开始、SQL 与提交回滚。
 *
 * <p>它只观察事务服务接口，不改变事务传播、异常或返回值；Redis 与 RabbitMQ 仍位于事务提交之后。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = {"enabled", "observability.enabled"},
        havingValue = "true")
public final class MembershipPaymentDatabaseTransactionTimingAspect {

    private final MembershipPaymentTimingRecorder recorder;

    public MembershipPaymentDatabaseTransactionTimingAspect(
            MembershipPaymentTimingRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    /** 该边界必须先于默认最低优先级的事务 Advisor 进入，并在 commit 或 rollback 完成后才停止计时。 */
    @Around(
            "execution(* com.example.temperate.service.user.membership.payment.order."
                    + "MembershipOrderCreationTransactionService.createOrGet(..))")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedNanos = System.nanoTime();
        boolean succeeded = false;
        try {
            Object result = joinPoint.proceed();
            succeeded = true;
            return result;
        } finally {
            recorder.recordDatabaseTransaction(
                    Math.max(0L, System.nanoTime() - startedNanos), succeeded);
        }
    }
}
