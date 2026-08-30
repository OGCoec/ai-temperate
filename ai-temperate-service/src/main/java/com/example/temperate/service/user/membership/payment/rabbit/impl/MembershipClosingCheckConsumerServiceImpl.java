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
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentTerminalQueryExhaustedException;
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
 * 该实现是来在订单进入 CLOSING 后立即复用原 Provider 关单逻辑，并在最终边界再次幂等确认后收敛 CLOSED。
 *
 * <p>安全关闭结果在硬截止前只安排最终确认；PAID 交给回调链收敛，UNKNOWN 依赖现有分段和终态有限重试。</p>
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
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentFactReconciliationService reconciliationService;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private final MembershipPaymentProperties properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    public MembershipClosingCheckConsumerServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipOrderSnapshotStore orderStore,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentFactReconciliationService reconciliationService,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentFinalCheckScheduler finalCheckScheduler,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
        this.finalCheckScheduler = Objects.requireNonNull(finalCheckScheduler);
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
        // 回调 marker 表示第三方成功通知已经交给回调 Worker；所有 CLOSING 阶段都必须停止续发和外部关单。
        if (orderStore.callbackInProgress(message.orderId())) {
            return;
        }
        OffsetDateTime boundaryCheckAt = MembershipPaymentTime.now(clock);
        if (order.closingDeadlineAt() == null) {
            retryTerminal(message);
            return;
        }
        // CLOSING 的首条消息与后续重试都走原关单方法；这只前移首次副作用，不改 Provider 合同。
        PaymentCloseResult close = closeOrUnknown(
                order, envelope.traceId(), envelope.messageId());
        if (close.status() == PaymentProviderStatus.PAID) {
            PaymentQueryResult paid = queryOrUnknown(
                    order, envelope.traceId(), envelope.messageId());
            reconciliationService.reconcilePaid(order, paid);
            if (boundaryCheckAt.isBefore(order.closingDeadlineAt())) {
                continueBeforeDeadline(message, delays, order.closingDeadlineAt());
            } else {
                retryTerminal(message);
            }
            return;
        }
        if (!safeClosedStatus(close.status())) {
            if (boundaryCheckAt.isBefore(order.closingDeadlineAt())) {
                continueBeforeDeadline(message, delays, order.closingDeadlineAt());
            } else {
                retryTerminal(message);
            }
            return;
        }
        if (boundaryCheckAt.isBefore(order.closingDeadlineAt())) {
            // 第三方已安全关闭也不提前写 CLOSED，五分钟 CLOSING 继续承接关单前已完成的延迟回调。
            finalCheckScheduler.scheduleClosing(
                    message.orderId(),
                    order.closingDeadlineAt(),
                    0);
            return;
        }

        MembershipOrderTransitionResult transition = orderStore.finalizeClosing(
                message.orderId(),
                close.status(),
                MembershipPaymentTime.now(clock));
        // 前置检查与 Lua 终态迁移之间仍可能并发写入 marker；此时同样把收敛权交给回调 Worker，禁止重新发布终态消息。
        if (transition.outcome()
                == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS) {
            return;
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.TOO_EARLY
                || transition.outcome()
                        == MembershipOrderTransitionOutcome.PROVIDER_STATUS_UNSAFE
                || transition.outcome() == MembershipOrderTransitionOutcome.MISSING) {
            retryTerminal(message);
        }
    }

    private void continueBeforeDeadline(
            MembershipClosingCheckMessage message,
            List<Long> delays,
            OffsetDateTime closingDeadlineAt) {
        int lastStage = delays.size() - 1;
        if (message.stageIndex() < lastStage) {
            int nextStage = message.stageIndex() + 1;
            closingPublisher.publishNext(
                    message.orderId(),
                    nextStage,
                    message.terminalRetryCount(),
                    Duration.ofMillis(delays.get(nextStage)));
            return;
        }
        // 零延迟首关单会让旧分段链提前到达最终阶段，仍必须回到订单真实硬截止时间。
        finalCheckScheduler.scheduleClosing(
                message.orderId(),
                closingDeadlineAt,
                message.terminalRetryCount());
    }

    private PaymentCloseResult closeOrUnknown(
            MembershipOrderSnapshot order,
            String traceId,
            String messageId) {
        try {
            MembershipPaymentProvider provider = providerRegistry.getRequired(
                    properties.defaultProvider());
            return provider.closePayment(new PaymentCloseCommand(
                    order.orderId(), order.providerTradeNo()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership closing provider close was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    exception.getClass().getSimpleName());
            return new PaymentCloseResult(
                    PaymentProviderStatus.UNKNOWN, order.providerTradeNo());
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
                    "Membership closing paid query was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    exception.getClass().getSimpleName());
            return PaymentQueryResult.unknown(order.orderId());
        }
    }

    private static boolean safeClosedStatus(PaymentProviderStatus status) {
        return status == PaymentProviderStatus.CLOSED
                || status == PaymentProviderStatus.EXPIRED
                || status == PaymentProviderStatus.FAILED
                || status == PaymentProviderStatus.REFUNDED;
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
