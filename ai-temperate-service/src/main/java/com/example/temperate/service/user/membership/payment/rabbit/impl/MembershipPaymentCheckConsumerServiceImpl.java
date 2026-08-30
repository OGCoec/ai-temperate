package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在 PENDING 最终边界查询支付事实，并在订单进入 CLOSING 后立即启动原关单消息链。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentCheckConsumerServiceImpl
        implements MembershipPaymentCheckConsumerService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipPaymentCheckConsumerServiceImpl.class);

    private final MembershipPaymentOrderLookupService lookupService;
    private final MembershipOrderSnapshotStore orderStore;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentFactReconciliationService reconciliationService;
    private final MembershipPaymentCheckPublisher paymentPublisher;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public MembershipPaymentCheckConsumerServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipOrderSnapshotStore orderStore,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentFactReconciliationService reconciliationService,
            MembershipPaymentCheckPublisher paymentPublisher,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentFinalCheckScheduler finalCheckScheduler,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.paymentPublisher = Objects.requireNonNull(paymentPublisher);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
        this.finalCheckScheduler = Objects.requireNonNull(finalCheckScheduler);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void process(
            MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope) {
        MembershipPaymentCheckMessage message = requireEnvelope(envelope);
        metrics.paymentCheck();
        List<Long> delays = properties.rabbit().paymentCheckDelaysMillis();
        requireStage(message.stageIndex(), delays.size());
        MembershipOrderSnapshot order = lookupService.find(message.orderId()).orElse(null);
        if (order == null || order.status() != MembershipOrderStatus.PENDING_PAYMENT) {
            return;
        }
        // 第三方成功回调写入 marker 后由回调 Worker 独占状态收敛；MQ 时间链不得继续续发或主动查询支付方。
        if (orderStore.callbackInProgress(message.orderId())) {
            return;
        }
        int lastStage = delays.size() - 1;
        if (message.stageIndex() < lastStage) {
            int nextStage = message.stageIndex() + 1;
            paymentPublisher.publishNext(
                    message.orderId(),
                    nextStage,
                    Duration.ofMillis(delays.get(nextStage)));
            return;
        }

        OffsetDateTime boundaryCheckAt = MembershipPaymentTime.now(clock);
        if (boundaryCheckAt.isBefore(order.expiresAt())) {
            // Rabbit 延迟只精确到毫秒；最终消费者仍以订单时间为准，禁止不足一毫秒的截断提前触发 Provider 查询。
            finalCheckScheduler.schedulePending(message.orderId(), order.expiresAt());
            return;
        }

        // 只有最后一段允许主动查询；PAID 结果仍进入统一 callback ready，禁止消费者直接更新订单或数据库。
        PaymentQueryResult provider = queryOrUnknown(
                order, envelope.traceId(), envelope.messageId());
        reconciliationService.reconcilePaid(order, provider);
        OffsetDateTime now = MembershipPaymentTime.now(clock);
        // 硬截止始终锚定订单过期时间；RabbitMQ 延迟或消费者抖动不得把可支付窗口继续向后延长。
        OffsetDateTime hardCloseAt = order.expiresAt().plus(properties.closingDuration());
        MembershipOrderTransitionResult transition = orderStore.startClosing(
                message.orderId(),
                hardCloseAt,
                now);
        if (transition.outcome() == MembershipOrderTransitionOutcome.APPLIED
                || transition.outcome() == MembershipOrderTransitionOutcome.ALREADY_APPLIED) {
            // 先原子写入 CLOSING，再零延迟发布现有关单消息；重复消息依赖 Provider 关单幂等和状态机收敛。
            closingPublisher.publishNext(
                    message.orderId(),
                    0,
                    0,
                    Duration.ZERO);
        }
    }

    private PaymentQueryResult queryOrUnknown(
            MembershipOrderSnapshot order,
            String traceId,
            String messageId) {
        try {
            metrics.paymentQuery();
            MembershipPaymentProvider provider = providerRegistry.getRequired(
                    properties.defaultProvider());
            return provider.queryPayment(new PaymentQueryCommand(
                    order.orderId(), order.providerTradeNo()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership payment final provider query was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    exception.getClass().getSimpleName());
            return PaymentQueryResult.unknown(order.orderId());
        }
    }

    private static MembershipPaymentCheckMessage requireEnvelope(
            MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope) {
        if (envelope == null
                || !MembershipPaymentRabbitNames.PAYMENT_EVENT.equals(envelope.eventType())
                || envelope.schemaVersion()
                        != MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Membership payment Rabbit envelope is invalid.");
        }
        return Objects.requireNonNull(envelope.payload());
    }

    private static void requireStage(int stage, int size) {
        if (stage < 0 || stage >= size) {
            throw new IllegalArgumentException("Membership payment check stage is invalid.");
        }
    }
}
