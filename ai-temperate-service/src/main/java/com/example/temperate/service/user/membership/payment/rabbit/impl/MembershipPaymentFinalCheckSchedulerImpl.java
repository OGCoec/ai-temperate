package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来依据 Clock 计算最终检查边界并选择配置列表最后阶段，避免新订单继续产生无业务动作的中间消息。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentFinalCheckSchedulerImpl
        implements MembershipPaymentFinalCheckScheduler {

    private final MembershipPaymentCheckPublisher paymentPublisher;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipPaymentProperties properties;
    private final Clock clock;

    public MembershipPaymentFinalCheckSchedulerImpl(
            MembershipPaymentCheckPublisher paymentPublisher,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentProperties properties,
            Clock clock) {
        this.paymentPublisher = Objects.requireNonNull(paymentPublisher);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void schedulePending(String orderId, OffsetDateTime expiresAt) {
        int finalStage = properties.rabbit().paymentCheckDelaysMillis().size() - 1;
        paymentPublisher.publishNext(
                orderId,
                finalStage,
                delayUntil(Objects.requireNonNull(expiresAt)));
    }

    @Override
    public void scheduleClosing(
            String orderId,
            OffsetDateTime hardCloseAt,
            int terminalRetryCount) {
        int finalStage = properties.rabbit().closingCheckDelaysMillis().size() - 1;
        closingPublisher.publishNext(
                orderId,
                finalStage,
                terminalRetryCount,
                delayUntil(Objects.requireNonNull(hardCloseAt)));
    }

    private Duration delayUntil(OffsetDateTime boundary) {
        Duration delay = Duration.between(clock.instant(), boundary.toInstant());
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
