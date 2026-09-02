package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.order.MembershipClosingFinalizationSource;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
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
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentTerminalQueryExhaustedException;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
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
 * <p>硬截止前所有非支付终态复用分段时间链；硬截止后无条件独立查询平台事实，并以来源受控的 Redis CAS 收敛。</p>
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
    private final MembershipPaymentAttemptTransactionService transactionService;
    private final HybridBase64UrlCodec base64UrlCodec;
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
            MembershipPaymentAttemptTransactionService transactionService,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipPaymentFinalCheckScheduler finalCheckScheduler,
            MembershipPaymentProperties properties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.lookupService = Objects.requireNonNull(lookupService);
        this.orderStore = Objects.requireNonNull(orderStore);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.transactionService = Objects.requireNonNull(transactionService);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
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
            logClose(
                    order,
                    envelope,
                    true,
                    "skipped",
                    "not_available",
                    "not_available",
                    PaymentProviderStatus.UNKNOWN,
                    "not_required",
                    "none",
                    "stop",
                    "CALLBACK_IN_PROGRESS");
            return;
        }
        OffsetDateTime boundaryCheckAt = MembershipPaymentTime.now(clock);
        if (order.closingDeadlineAt() == null) {
            logClose(
                    order,
                    envelope,
                    false,
                    "skipped",
                    "not_available",
                    "not_available",
                    PaymentProviderStatus.UNKNOWN,
                    "not_required",
                    "none",
                    "retry_close",
                    "PROVIDER_STATUS_UNSAFE");
            retryTerminal(message, order, envelope);
            return;
        }
        // 关单尝试只是一项外部副作用及诊断事实，不能替代最终查询，更不能独自决定本地 CLOSED。
        CloseAttempt close = closeOrUnknown(
                order, envelope.traceId(), envelope.messageId());
        if (boundaryCheckAt.isBefore(order.closingDeadlineAt())) {
            continueClosingBeforeDeadline(message, delays, order, close, envelope);
            return;
        }

        // 截止时间一到，close 的任何成功或失败都不得跳过这次独立查询。
        FinalQueryAttempt finalQuery = queryFinal(
                order, envelope.traceId(), envelope.messageId());
        finalizeAtClosingDeadline(message, order, close, finalQuery, envelope);
    }

    private void continueClosingBeforeDeadline(
            MembershipClosingCheckMessage message,
            List<Long> delays,
            MembershipOrderSnapshot order,
            CloseAttempt close,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope) {
        logCloseResult(order, envelope, close, MembershipPaymentTime.now(clock));
        if (close.result().status() == PaymentProviderStatus.PAID) {
            FinalQueryAttempt paidQuery = queryFinal(
                    order, envelope.traceId(), envelope.messageId());
            if (paidQuery.result().status() == PaymentProviderStatus.PAID) {
                reconciliationService.reconcilePaid(order, paidQuery.result());
            }
        }
        if (safeClosedStatus(close.result().status())) {
            // 平台已关闭也保留完整回调窗口，避免把关单前已完成但迟到的支付通知误判为普通超时。
            finalCheckScheduler.scheduleClosing(
                    message.orderId(), order.closingDeadlineAt(), 0);
            return;
        }
        continueBeforeDeadline(message, delays, order.closingDeadlineAt());
    }

    private void finalizeAtClosingDeadline(
            MembershipClosingCheckMessage message,
            MembershipOrderSnapshot order,
            CloseAttempt close,
            FinalQueryAttempt finalQuery,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope) {
        PaymentProviderStatus status = finalQuery.result().status();
        if (status == PaymentProviderStatus.PAID) {
            boolean accepted = reconciliationService.reconcilePaid(order, finalQuery.result());
            logFinalization(
                    order,
                    envelope,
                    close,
                    finalQuery,
                    null,
                    false,
                    "none",
                    accepted ? "callback_worker" : "retry_query",
                    "FINAL_QUERY_PAID");
            if (!accepted) {
                retryTerminal(message, order, envelope);
            }
            return;
        }

        MembershipClosingFinalizationSource source = safeClosedStatus(status)
                ? MembershipClosingFinalizationSource.PROVIDER_CONFIRMED
                : MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED;
        PaymentProviderStatus observed = safeClosedStatus(status)
                ? status
                : status == PaymentProviderStatus.UNKNOWN
                        ? PaymentProviderStatus.UNKNOWN
                        : PaymentProviderStatus.PENDING;

        MembershipOrderTransitionResult transition;
        try {
            // Lua 在写 CLOSED 前再次检查当前状态、deadline 与 callback marker，消除外部查询后的竞态窗口。
            transition = orderStore.finalizeClosing(
                    message.orderId(),
                    observed,
                    source,
                    MembershipPaymentTime.now(clock));
        } catch (MembershipPaymentInfrastructureException exception) {
            logFinalization(
                    order,
                    envelope,
                    close,
                    finalQuery,
                    source,
                    false,
                    "none",
                    "retry_query",
                    "FINALIZATION_INFRASTRUCTURE_RETRY");
            retryTerminal(message, order, envelope);
            return;
        }

        switch (transition.outcome()) {
            case APPLIED -> {
                String reason = source
                                == MembershipClosingFinalizationSource.PROVIDER_CONFIRMED
                        ? "FINALIZED_CLOSED_PROVIDER_CONFIRMED"
                        : "FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED";
                logFinalization(
                        order,
                        envelope,
                        close,
                        finalQuery,
                        source,
                        false,
                        "closing_to_closed",
                        "stop",
                        reason);
            }
            case ALREADY_APPLIED -> logFinalization(
                    order,
                    envelope,
                    close,
                    finalQuery,
                    source,
                    false,
                    "already_closed",
                    "stop",
                    "FINALIZATION_IDEMPOTENT");
            case CALLBACK_IN_PROGRESS -> logFinalization(
                    order,
                    envelope,
                    close,
                    finalQuery,
                    source,
                    true,
                    "none",
                    "callback_worker",
                    "FINALIZATION_CALLBACK_IN_PROGRESS");
            case NOT_ALLOWED -> {
                if (!terminalStatus(transition.status())) {
                    throw new IllegalStateException(
                            "Closing finalization was rejected from a non-terminal state.");
                }
                logFinalization(
                        order,
                        envelope,
                        close,
                        finalQuery,
                        source,
                        false,
                        "concurrent_terminal",
                        "stop",
                        "FINALIZATION_CONCURRENT_TERMINAL");
            }
            case TOO_EARLY, MISSING -> retryTerminal(message, order, envelope);
            case PROVIDER_STATUS_UNSAFE -> throw new IllegalStateException(
                    "Closing finalization source and provider status are inconsistent.");
            default -> throw new IllegalStateException(
                    "Unexpected closing transition outcome: " + transition.outcome());
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

    private CloseAttempt closeOrUnknown(
            MembershipOrderSnapshot order,
            String traceId,
            String messageId) {
        boolean requestSent = false;
        try {
            MembershipOrderSnapshot routed = ensureTradeReference(
                    order, "closing_discovery", traceId, messageId);
            MembershipPaymentProvider provider = providerRegistry.getRequired(
                    PaymentProviderReference.resolveTrade(routed.providerTradeNo()));
            requestSent = true;
            PaymentCloseResult result = provider.closePayment(new PaymentCloseCommand(
                    routed.orderId(), routed.providerTradeNo()));
            if (result == null || result.status() == null) {
                throw new IllegalStateException("Provider close result is incomplete.");
            }
            bindResolvedProviderTradeNo(
                    routed,
                    result.providerTradeNo(),
                    "close_response",
                    traceId,
                    messageId);
            return new CloseAttempt(
                    result,
                    true,
                    "sent",
                    "success",
                    "verified",
                    closeResultReason(result.status()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership closing provider close was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    failureReason(exception));
            String reason = closeFailureReason(exception);
            return new CloseAttempt(
                    new PaymentCloseResult(
                            PaymentProviderStatus.UNKNOWN, order.providerTradeNo()),
                    false,
                    requestSent ? "sent" : "skipped",
                    requestSent ? "failed" : "not_available",
                    "CLOSE_SIGNATURE_INVALID".equals(reason)
                            ? "failed"
                            : "not_available",
                    reason);
        }
    }

    private FinalQueryAttempt queryFinal(
            MembershipOrderSnapshot order,
            String traceId,
            String messageId) {
        boolean requestSent = false;
        PaymentQueryResult result;
        try {
            MembershipOrderSnapshot routed = ensureTradeReference(
                    order, "closing_query_discovery", traceId, messageId);
            metrics.paymentQuery();
            MembershipPaymentProvider provider = providerRegistry.getRequired(
                    PaymentProviderReference.resolveTrade(routed.providerTradeNo()));
            requestSent = true;
            result = provider.queryPayment(new PaymentQueryCommand(
                    routed.orderId(), routed.providerTradeNo()));
            if (result == null || result.status() == null) {
                throw new IllegalStateException("Provider query result is incomplete.");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership closing confirmation query was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    failureReason(exception));
            String signatureOutcome = exception instanceof MembershipPaymentException payment
                            && payment.code()
                                    == MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID
                    ? "failed"
                    : "not_available";
            return new FinalQueryAttempt(
                    PaymentQueryResult.unknown(order.orderId()),
                    false,
                    requestSent ? "sent" : "skipped",
                    requestSent ? "failed" : "not_available",
                    signatureOutcome,
                    "FINAL_QUERY_FAILED");
        }
        // 平台查询事实已经可信后，引用持久化失败属于本地基础设施问题，不能降级伪装成 Provider UNKNOWN 后强制关闭。
        bindResolvedProviderTradeNo(
                order,
                result.providerTradeNo(),
                "closing_query",
                traceId,
                messageId);
        return new FinalQueryAttempt(
                result,
                true,
                "sent",
                "success",
                "verified",
                finalQueryReason(result.status()));
    }

    /** 可信发现或回调结果只允许把空引用绑定为真实 TRADE；既有不同流水绝不覆盖。 */
    private void bindResolvedProviderTradeNo(
            MembershipOrderSnapshot order,
            String resolvedReference,
            String source,
            String traceId,
            String messageId) {
        String currentReference = order.providerTradeNo();
        if (resolvedReference == null) {
            return;
        }
        PaymentProviderType resolvedProvider = PaymentProviderReference.resolveTrade(
                resolvedReference);
        if (currentReference != null) {
            if (!currentReference.equals(resolvedReference)) {
                throw providerTradeConflict();
            }
            return;
        }
        if (!properties.publicProviders().contains(resolvedProvider)) {
            throw providerTradeConflict();
        }
        transactionService.bindProviderTradeNo(
                order.loginIdentityId(),
                base64UrlCodec.decode(order.orderId()),
                resolvedReference);
        MembershipProviderTradeNoPatchOutcome outcome = orderStore.patchProviderTradeNo(
                order.orderId(), order.loginIdentityId(), resolvedReference);
        if (outcome == MembershipProviderTradeNoPatchOutcome.CONFLICT) {
            throw providerTradeConflict();
        }
        Objects.requireNonNull(outcome, "Provider trade number patch outcome must not be null");
        String redisBind = switch (outcome) {
            case APPLIED -> "applied";
            case UNCHANGED -> "unchanged";
            case MISSING -> restoreMissingRedisReference(order, resolvedReference);
            case CONFLICT -> throw providerTradeConflict();
        };
        MembershipPaymentLifecycleDiagnostics.referenceBound(
                order,
                resolvedProvider,
                source,
                "applied",
                redisBind,
                MembershipPaymentTime.now(clock),
                traceId,
                messageId);
    }

    private MembershipOrderSnapshot ensureTradeReference(
            MembershipOrderSnapshot order,
            String source,
            String traceId,
            String messageId) {
        if (order.providerTradeNo() != null) {
            PaymentProviderReference.resolveTrade(order.providerTradeNo());
            return order;
        }
        PaymentQueryResult discovered = null;
        boolean unknownSeen = false;
        for (PaymentProviderType candidate : properties.publicProviders()) {
            if (!providerEnabled(candidate)) {
                continue;
            }
            try {
                metrics.paymentQuery();
                PaymentQueryResult result = providerRegistry.getRequired(candidate)
                        .queryPayment(new PaymentQueryCommand(order.orderId(), null));
                if (result != null
                        && PaymentProviderReference.isTrade(
                                candidate, result.providerTradeNo())) {
                    if (discovered != null) {
                        throw providerTradeConflict();
                    }
                    discovered = result;
                } else {
                    unknownSeen = true;
                }
            } catch (RuntimeException exception) {
                if (exception instanceof MembershipPaymentException paymentException
                        && paymentException.code()
                                == MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT) {
                    throw paymentException;
                }
                // Provider 没有明确、可验签的 NOT_FOUND 合同时，任何异常都必须保守归类 UNKNOWN。
                unknownSeen = true;
            }
        }
        if (discovered == null || unknownSeen) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                    "External payment discovery is still uncertain.");
        }
        bindResolvedProviderTradeNo(
                order,
                discovered.providerTradeNo(),
                source,
                traceId,
                messageId);
        return withProviderTradeNo(order, discovered.providerTradeNo());
    }

    private boolean providerEnabled(PaymentProviderType provider) {
        return switch (provider) {
            case BAR -> properties.bar().enabled();
            case LIUHAO -> properties.liuhao().enabled();
            case LOCAL_SIMULATOR -> false;
        };
    }

    private String restoreMissingRedisReference(
            MembershipOrderSnapshot order,
            String resolvedReference) {
        MembershipOrderSnapshot restored = orderStore.putAndGet(
                withProviderTradeNo(order, resolvedReference));
        if (!Objects.equals(restored.providerTradeNo(), resolvedReference)) {
            throw providerTradeConflict();
        }
        return "applied";
    }

    private static MembershipOrderSnapshot withProviderTradeNo(
            MembershipOrderSnapshot source,
            String providerTradeNo) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(),
                source.orderId(),
                source.loginIdentityId(),
                source.membershipTier(),
                source.payAmountYuan(),
                source.payType(),
                source.status(),
                source.idempotencyKey(),
                providerTradeNo,
                source.paymentStartedAt(),
                source.expiresAt(),
                source.closingDeadlineAt(),
                source.paidAt(),
                source.stateVersion(),
                source.createdAt(),
                source.updatedAt());
    }

    private static MembershipPaymentException providerTradeConflict() {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                "The provider trade number conflicts with the current order.");
    }

    private static String failureReason(RuntimeException exception) {
        return exception instanceof MembershipPaymentException paymentException
                ? paymentException.code().name()
                : exception.getClass().getSimpleName();
    }

    private static String closeFailureReason(RuntimeException exception) {
        if (exception instanceof MembershipPaymentException paymentException) {
            return switch (paymentException.code()) {
                case PAYMENT_PROVIDER_UNSUPPORTED -> "CLOSE_PLUGIN_UNSUPPORTED";
                case LIUHAO_SIGNATURE_INVALID -> "CLOSE_SIGNATURE_INVALID";
                case LIUHAO_RESPONSE_INVALID, LIUHAO_ORDER_CONFLICT ->
                        "CLOSE_RESPONSE_INVALID";
                default -> "CLOSE_REQUEST_FAILED";
            };
        }
        return "CLOSE_REQUEST_FAILED";
    }

    private static String closeResultReason(PaymentProviderStatus status) {
        return switch (status) {
            case PAID -> "PROVIDER_PAID_DURING_CLOSE";
            case PENDING -> "FOLLOWUP_QUERY_PENDING";
            case UNKNOWN -> "FOLLOWUP_QUERY_UNKNOWN";
            case CLOSED, EXPIRED, FAILED, REFUNDED ->
                    "CLOSE_CONFIRMED_WAITING_CALLBACK_WINDOW";
        };
    }

    private static String finalQueryReason(PaymentProviderStatus status) {
        return switch (status) {
            case PAID -> "FINAL_QUERY_PAID";
            case PENDING -> "FINAL_QUERY_PENDING";
            case UNKNOWN -> "FINAL_QUERY_UNKNOWN";
            case CLOSED, EXPIRED, FAILED, REFUNDED -> "FINAL_QUERY_CONFIRMED_CLOSED";
        };
    }

    private static boolean safeClosedStatus(PaymentProviderStatus status) {
        return status == PaymentProviderStatus.CLOSED
                || status == PaymentProviderStatus.EXPIRED
                || status == PaymentProviderStatus.FAILED
                || status == PaymentProviderStatus.REFUNDED;
    }

    private static boolean terminalStatus(MembershipOrderStatus status) {
        return status == MembershipOrderStatus.PAID
                || status == MembershipOrderStatus.CANCELLED
                || status == MembershipOrderStatus.CLOSED;
    }

    private void retryTerminal(
            MembershipClosingCheckMessage message,
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope) {
        if (message.terminalRetryCount()
                >= properties.rabbit().terminalQueryMaxRetries()) {
            logClose(
                    order,
                    envelope,
                    false,
                    "failed",
                    "failed",
                    "not_available",
                    PaymentProviderStatus.UNKNOWN,
                    "failed",
                    "none",
                    "keep_closing",
                    "TERMINAL_RETRY_EXHAUSTED");
            throw new MembershipPaymentTerminalQueryExhaustedException();
        }
        logClose(
                order,
                envelope,
                false,
                "failed",
                "failed",
                "not_available",
                PaymentProviderStatus.UNKNOWN,
                "failed",
                "none",
                "retry_close",
                "TERMINAL_RETRY_SCHEDULED");
        closingPublisher.publishNext(
                message.orderId(),
                message.stageIndex(),
                message.terminalRetryCount() + 1,
                properties.rabbit().terminalQueryRetryDelay());
    }

    private void logCloseResult(
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope,
            CloseAttempt close,
            OffsetDateTime checkedAt) {
        boolean beforeDeadline = checkedAt.isBefore(order.closingDeadlineAt());
        PaymentProviderStatus status = close.result().status();
        if (!beforeDeadline && safeClosedStatus(status)) {
            // 安全终态只有在 Lua 最终迁移成功后才能记录 FINALIZED_CLOSED，避免把关单响应误写成本地终态。
            return;
        }
        String followup = switch (status) {
            case PENDING -> "still_pending";
            case PAID -> "paid";
            case UNKNOWN -> "unknown";
            case CLOSED, EXPIRED, FAILED, REFUNDED -> "confirmed_closed";
        };
        String nextAction = switch (status) {
            case PAID -> "reconcile_paid";
            case CLOSED, EXPIRED, FAILED, REFUNDED -> beforeDeadline
                    ? "wait_callback_window" : "finalize_closed";
            case PENDING, UNKNOWN -> "retry_close";
        };
        logClose(
                order,
                envelope,
                false,
                close.requestOutcome(),
                close.httpOutcome(),
                close.signatureOutcome(),
                status,
                followup,
                "none",
                nextAction,
                close.reason());
    }

    private void logFinalization(
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope,
            CloseAttempt close,
            FinalQueryAttempt finalQuery,
            MembershipClosingFinalizationSource source,
            boolean callbackMarker,
            String transition,
            String nextAction,
            String reason) {
        MembershipPaymentLifecycleDiagnostics.closingFinalization(
                order,
                PaymentProviderReference.tryResolveTrade(order.providerTradeNo()),
                callbackMarker,
                close.trusted(),
                finalQuery.requestOutcome(),
                finalQuery.httpOutcome(),
                finalQuery.signatureOutcome(),
                finalQuery.result().status(),
                source,
                transition,
                nextAction,
                reason,
                envelope.traceId(),
                envelope.messageId());
    }

    private void logClose(
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope,
            boolean callbackMarker,
            String closeRequest,
            String httpOutcome,
            String signatureOutcome,
            PaymentProviderStatus providerStatus,
            String followupQuery,
            String transition,
            String nextAction,
            String reason) {
        PaymentProviderType provider = PaymentProviderReference.tryResolveTrade(
                order.providerTradeNo());
        OffsetDateTime now = MembershipPaymentTime.now(clock);
        String trigger = order.closingDeadlineAt() != null
                && !now.isBefore(order.closingDeadlineAt())
                ? "final_boundary"
                : "closing_retry";
        MembershipPaymentLifecycleDiagnostics.closeLifecycle(
                order,
                provider,
                trigger,
                callbackMarker,
                closeRequest,
                httpOutcome,
                signatureOutcome,
                provider == PaymentProviderType.LIUHAO && "success".equals(httpOutcome)
                        ? "0" : "not_available",
                providerStatus,
                PaymentProviderReference.rawTradeNo(order.providerTradeNo()) != null,
                followupQuery,
                transition,
                nextAction,
                reason,
                envelope.traceId(),
                envelope.messageId());
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

    private record CloseAttempt(
            PaymentCloseResult result,
            boolean trusted,
            String requestOutcome,
            String httpOutcome,
            String signatureOutcome,
            String reason) {
    }

    private record FinalQueryAttempt(
            PaymentQueryResult result,
            boolean trusted,
            String requestOutcome,
            String httpOutcome,
            String signatureOutcome,
            String reason) {
    }
}
