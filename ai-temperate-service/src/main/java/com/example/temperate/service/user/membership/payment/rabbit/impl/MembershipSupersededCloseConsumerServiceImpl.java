package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentTerminalQueryExhaustedException;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededCloseConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededCloseMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededClosePublisher;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来对本地已 CLOSED 的替换旧单执行第三方关单、可信查询和有限重试，并把已支付事实交给退款链。
 *
 * <p>本服务禁止修改本地订单状态；旧单的 CLOSED 事实必须在消息发布前已经由创建链写入。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipSupersededCloseConsumerServiceImpl
        implements MembershipSupersededCloseConsumerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MembershipSupersededCloseConsumerServiceImpl.class);

    private final MembershipPaymentOrderLookupService lookupService;
    private final MembershipOrderSnapshotStore orderStore;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentFactReconciliationService reconciliationService;
    private final MembershipPaymentAttemptTransactionService transactionService;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipSupersededClosePublisher publisher;
    private final MembershipPaymentProperties properties;
    private final MembershipPaymentMetrics metrics;

    public MembershipSupersededCloseConsumerServiceImpl(
            MembershipPaymentOrderLookupService lookupService,
            MembershipOrderSnapshotStore orderStore,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentFactReconciliationService reconciliationService,
            MembershipPaymentAttemptTransactionService transactionService,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipSupersededClosePublisher publisher,
            MembershipPaymentProperties properties,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.transactionService = Objects.requireNonNull(transactionService);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.publisher = Objects.requireNonNull(publisher);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
    }

    /**
     * 只处理本地已 CLOSED 的替换旧单；先尝试关单，再以查询确认终态，已支付事实交给既有回调退款编排。
     */
    @Override
    public void process(
            MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope) {
        MembershipSupersededCloseMessage message = requireEnvelope(envelope);
        MembershipOrderSnapshot order = lookupService.find(message.orderId()).orElse(null);
        if (order == null || order.status() != MembershipOrderStatus.CLOSED) {
            return;
        }
        metrics.closing();
        if (orderStore.callbackInProgress(order.orderId())) {
            log(envelope, "callback_worker", "CALLBACK_IN_PROGRESS");
            return;
        }

        MembershipOrderSnapshot routed;
        try {
            routed = ensureTradeReference(order);
        } catch (RuntimeException exception) {
            log(envelope, "retry_close", failureReason(exception));
            retry(message);
            return;
        }
        MembershipPaymentProvider provider = providerRegistry.getRequired(
                PaymentProviderReference.resolveTrade(routed.providerTradeNo()));
        PaymentCloseResult close = close(provider, routed, envelope);
        if (close != null && safeTerminal(close.status())) {
            log(envelope, "stop", "PROVIDER_CLOSE_CONFIRMED");
            return;
        }

        PaymentQueryResult query = query(provider, routed, envelope);
        if (query.status() == PaymentProviderStatus.PAID) {
            boolean accepted = reconciliationService.reconcilePaid(routed, query);
            if (accepted) {
                log(envelope, "callback_worker", "PROVIDER_PAID_REFUND_REQUIRED");
                return;
            }
        } else if (safeTerminal(query.status())) {
            log(envelope, "stop", "PROVIDER_QUERY_CONFIRMED_CLOSED");
            return;
        }
        log(envelope, "retry_close", "PROVIDER_STATUS_UNCERTAIN");
        retry(message);
    }

    private PaymentCloseResult close(
            MembershipPaymentProvider provider,
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope) {
        try {
            PaymentCloseResult result = provider.closePayment(new PaymentCloseCommand(
                    order.orderId(), order.providerTradeNo()));
            if (result == null || result.status() == null) {
                throw new IllegalStateException("Provider close result is incomplete.");
            }
            requireSameTrade(order.providerTradeNo(), result.providerTradeNo());
            return result;
        } catch (RuntimeException exception) {
            log(envelope, "query_provider", failureReason(exception));
            return null;
        }
    }

    private PaymentQueryResult query(
            MembershipPaymentProvider provider,
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope) {
        try {
            metrics.paymentQuery();
            PaymentQueryResult result = provider.queryPayment(new PaymentQueryCommand(
                    order.orderId(), order.providerTradeNo()));
            if (result == null || result.status() == null) {
                throw new IllegalStateException("Provider query result is incomplete.");
            }
            requireSameTrade(order.providerTradeNo(), result.providerTradeNo());
            return result;
        } catch (RuntimeException exception) {
            log(envelope, "retry_close", failureReason(exception));
            return PaymentQueryResult.unknown(order.orderId());
        }
    }

    /** 缺失流水时必须遍历已启用公开 Provider；任一来源不确定都禁止猜测路由。 */
    private MembershipOrderSnapshot ensureTradeReference(MembershipOrderSnapshot order) {
        if (order.providerTradeNo() != null) {
            PaymentProviderReference.resolveTrade(order.providerTradeNo());
            return order;
        }
        PaymentQueryResult discovered = null;
        boolean uncertain = false;
        for (PaymentProviderType candidate : properties.publicProviders()) {
            if (!providerEnabled(candidate)) {
                continue;
            }
            try {
                metrics.paymentQuery();
                PaymentQueryResult result = providerRegistry.getRequired(candidate)
                        .queryPayment(new PaymentQueryCommand(order.orderId(), null));
                if (result != null
                        && PaymentProviderReference.isTrade(candidate, result.providerTradeNo())) {
                    if (discovered != null) {
                        throw providerTradeConflict();
                    }
                    discovered = result;
                } else {
                    uncertain = true;
                }
            } catch (MembershipPaymentException exception) {
                if (exception.code()
                        == MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT) {
                    throw exception;
                }
                uncertain = true;
            } catch (RuntimeException exception) {
                uncertain = true;
            }
        }
        if (discovered == null || uncertain) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                    "External payment discovery is still uncertain.");
        }
        bindDiscoveredTrade(order, discovered.providerTradeNo());
        return withProviderTradeNo(order, discovered.providerTradeNo());
    }

    private void bindDiscoveredTrade(MembershipOrderSnapshot order, String reference) {
        PaymentProviderReference.resolveTrade(reference);
        transactionService.bindProviderTradeNo(
                order.loginIdentityId(),
                base64UrlCodec.decode(order.orderId()),
                reference);
        MembershipProviderTradeNoPatchOutcome outcome = orderStore.patchProviderTradeNo(
                order.orderId(), order.loginIdentityId(), reference);
        if (outcome == MembershipProviderTradeNoPatchOutcome.MISSING) {
            MembershipOrderSnapshot restored = orderStore.putAndGet(
                    withProviderTradeNo(order, reference));
            if (!Objects.equals(restored.providerTradeNo(), reference)) {
                throw providerTradeConflict();
            }
            return;
        }
        if (outcome == null || outcome == MembershipProviderTradeNoPatchOutcome.CONFLICT) {
            throw providerTradeConflict();
        }
    }

    private void retry(MembershipSupersededCloseMessage message) {
        if (message.retryCount() >= properties.rabbit().terminalQueryMaxRetries()) {
            throw new MembershipPaymentTerminalQueryExhaustedException();
        }
        publisher.publish(
                message.orderId(),
                message.retryCount() + 1,
                properties.rabbit().terminalQueryRetryDelay());
    }

    private boolean providerEnabled(PaymentProviderType provider) {
        return switch (provider) {
            case BAR -> properties.bar().enabled();
            case LIUHAO -> properties.liuhao().enabled();
            case LOCAL_SIMULATOR -> false;
        };
    }

    private static MembershipSupersededCloseMessage requireEnvelope(
            MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope) {
        if (envelope == null
                || !MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EVENT.equals(
                        envelope.eventType())) {
            throw new IllegalArgumentException("Superseded close envelope is invalid.");
        }
        return Objects.requireNonNull(envelope.payload());
    }

    private static void requireSameTrade(String expected, String actual) {
        if (actual != null && !Objects.equals(expected, actual)) {
            throw providerTradeConflict();
        }
    }

    private static MembershipPaymentException providerTradeConflict() {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                "The provider trade number conflicts with the replaced order.");
    }

    private static boolean safeTerminal(PaymentProviderStatus status) {
        return status == PaymentProviderStatus.CLOSED
                || status == PaymentProviderStatus.EXPIRED
                || status == PaymentProviderStatus.FAILED
                || status == PaymentProviderStatus.REFUNDED;
    }

    private static String failureReason(RuntimeException exception) {
        return exception instanceof MembershipPaymentException payment
                ? payment.code().name()
                : exception.getClass().getSimpleName();
    }

    private static MembershipOrderSnapshot withProviderTradeNo(
            MembershipOrderSnapshot source,
            String reference) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(), source.orderId(), source.loginIdentityId(),
                source.membershipTier(), source.payAmountYuan(), source.payType(),
                source.status(), source.idempotencyKey(), reference,
                source.paymentStartedAt(), source.expiresAt(), source.closingDeadlineAt(),
                source.paidAt(), source.stateVersion(), source.createdAt(), source.updatedAt());
    }

    private static void log(
            MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope,
            String nextAction,
            String reason) {
        LOGGER.info(
                "event=membership_order_superseded_close next_action={} reason={} "
                        + "traceId={} messageId={}",
                nextAction,
                reason,
                envelope.traceId(),
                envelope.messageId());
    }
}
