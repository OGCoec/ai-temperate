package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentStatusQueryService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来处理 PENDING 的九段五分钟检查；前八段只续发消息，最终段才查询模拟平台并进入五分钟 CLOSING 缓冲。
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
    private final SimulatedPaymentStatusQueryService statusQueryService;
    private final PaymentCallbackQueue callbackQueue;
    private final MembershipPaymentCheckPublisher paymentPublisher;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public MembershipPaymentCheckConsumerServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipOrderSnapshotStore orderStore,
            SimulatedPaymentStatusQueryService statusQueryService,
            PaymentCallbackQueue callbackQueue,
            MembershipPaymentCheckPublisher paymentPublisher,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.statusQueryService = Objects.requireNonNull(statusQueryService);
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.paymentPublisher = Objects.requireNonNull(paymentPublisher);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
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
        int lastStage = delays.size() - 1;
        if (message.stageIndex() < lastStage) {
            int nextStage = message.stageIndex() + 1;
            paymentPublisher.publishNext(
                    message.orderId(),
                    nextStage,
                    Duration.ofMillis(delays.get(nextStage)));
            return;
        }

        // 只有最后一段允许主动查询；PAID 结果仍进入统一 callback ready，禁止消费者直接更新订单或数据库。
        SimulatedPaymentProviderResult provider = queryOrUnknown(
                message.orderId(), envelope.traceId(), envelope.messageId());
        ensurePaidCallbackReady(provider);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // 硬截止始终锚定订单过期时间；RabbitMQ 延迟或消费者抖动不得把可支付窗口继续向后延长。
        OffsetDateTime hardCloseAt = order.expiresAt().plus(properties.closingDuration());
        MembershipOrderTransitionResult transition = orderStore.startClosing(
                message.orderId(),
                hardCloseAt,
                now);
        if (transition.outcome() == MembershipOrderTransitionOutcome.APPLIED
                || transition.outcome() == MembershipOrderTransitionOutcome.ALREADY_APPLIED) {
            closingPublisher.publishNext(
                    message.orderId(),
                    0,
                    0,
                    Duration.ofMillis(
                            properties.rabbit().closingCheckDelaysMillis().get(0)));
        }
    }

    private SimulatedPaymentProviderResult queryOrUnknown(
            String orderId,
            String traceId,
            String messageId) {
        try {
            metrics.paymentQuery();
            return statusQueryService.query(orderId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership payment final provider query was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    exception.getClass().getSimpleName());
            return new SimulatedPaymentProviderResult(
                    SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                    orderId,
                    SimulatedPaymentProviderStatus.UNKNOWN,
                    null,
                    null,
                    null,
                    null,
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        }
    }

    private void ensurePaidCallbackReady(SimulatedPaymentProviderResult provider) {
        if (provider.status() == SimulatedPaymentProviderStatus.PAID
                && provider.callbackId() != null) {
            callbackQueue.ensureReady(provider.callbackId(), clock.millis());
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
