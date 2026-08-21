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
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentTerminalQueryExhaustedException;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来处理 CLOSING 的五段五分钟检查；最终只有平台明确 UNPAID、无 marker 且截止时间到达才允许 Lua 转为 CLOSED。
 *
 * <p>PAID、UNKNOWN、查询异常和 marker 均使用三次三十秒有限重试；耗尽后抛出专用异常进入 DLQ，订单保持 CLOSING。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipClosingCheckConsumerServiceImpl
        implements MembershipClosingCheckConsumerService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipClosingCheckConsumerServiceImpl.class);

    private final MembershipPaymentOrderLookupService lookupService;
    private final MembershipOrderSnapshotStore orderStore;
    private final SimulatedPaymentStatusQueryService statusQueryService;
    private final PaymentCallbackQueue callbackQueue;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public MembershipClosingCheckConsumerServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipOrderSnapshotStore orderStore,
            SimulatedPaymentStatusQueryService statusQueryService,
            PaymentCallbackQueue callbackQueue,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.statusQueryService = Objects.requireNonNull(statusQueryService);
        this.callbackQueue = Objects.requireNonNull(callbackQueue);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void process(
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope) {
        MembershipClosingCheckMessage message = requireEnvelope(envelope);
        metrics.closing();
        List<Long> delays = properties.rabbit().closingCheckDelaysMillis();
        requireStage(message.stageIndex(), delays.size());
        MembershipOrderSnapshot order = lookupService.find(message.orderId()).orElse(null);
        if (order == null || order.status() != MembershipOrderStatus.CLOSING) {
            return;
        }
        int lastStage = delays.size() - 1;
        if (message.stageIndex() < lastStage) {
            int nextStage = message.stageIndex() + 1;
            closingPublisher.publishNext(
                    message.orderId(),
                    nextStage,
                    0,
                    Duration.ofMillis(delays.get(nextStage)));
            return;
        }
        if (orderStore.callbackInProgress(message.orderId())) {
            retryTerminal(message);
            return;
        }

        SimulatedPaymentProviderResult provider = queryOrUnknown(
                message.orderId(), envelope.traceId(), envelope.messageId());
        if (provider.status() == SimulatedPaymentProviderStatus.PAID) {
            if (provider.callbackId() == null
                    || !callbackQueue.ensureReady(provider.callbackId(), clock.millis())) {
                retryTerminal(message);
                return;
            }
            retryTerminal(message);
            return;
        }
        if (provider.status() != SimulatedPaymentProviderStatus.UNPAID) {
            retryTerminal(message);
            return;
        }

        MembershipOrderTransitionResult transition = orderStore.finalizeClosing(
                message.orderId(),
                java.time.OffsetDateTime.ofInstant(
                        clock.instant(), java.time.ZoneOffset.UTC));
        if (transition.outcome() == MembershipOrderTransitionOutcome.TOO_EARLY
                || transition.outcome()
                        == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS
                || transition.outcome()
                        == MembershipOrderTransitionOutcome.PROVIDER_STATUS_UNSAFE
                || transition.outcome() == MembershipOrderTransitionOutcome.MISSING) {
            retryTerminal(message);
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
                    "Membership closing provider query was UNKNOWN; "
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
                    java.time.OffsetDateTime.ofInstant(
                            clock.instant(), java.time.ZoneOffset.UTC));
        }
    }

    private void retryTerminal(MembershipClosingCheckMessage message) {
        if (message.terminalRetryCount()
                >= properties.rabbit().terminalQueryMaxRetries()) {
            throw new MembershipPaymentTerminalQueryExhaustedException();
        }
        closingPublisher.publishNext(
                message.orderId(),
                message.stageIndex(),
                message.terminalRetryCount() + 1,
                properties.rabbit().terminalQueryRetryDelay());
    }

    private static MembershipClosingCheckMessage requireEnvelope(
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope) {
        if (envelope == null
                || !MembershipPaymentRabbitNames.CLOSING_EVENT.equals(envelope.eventType())
                || envelope.schemaVersion()
                        != MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Membership closing Rabbit envelope is invalid.");
        }
        return Objects.requireNonNull(envelope.payload());
    }

    private static void requireStage(int stage, int size) {
        if (stage < 0 || stage >= size) {
            throw new IllegalArgumentException("Membership closing check stage is invalid.");
        }
    }
}
