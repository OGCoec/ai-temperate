package com.example.temperate.service.user.membership.payment.rabbit.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
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
 * 该实现是来在六号支付发起后分段发现平台流水，并在 PENDING 最终边界查询支付事实和启动原关单消息链。
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
    private final MembershipPaymentAttemptTransactionService transactionService;
    private final HybridBase64UrlCodec base64UrlCodec;
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
            MembershipPaymentAttemptTransactionService transactionService,
            HybridBase64UrlCodec base64UrlCodec,
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
        this.transactionService = Objects.requireNonNull(transactionService);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
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
            logCallbackInProgress(order, envelope, message.stageIndex());
            return;
        }
        int lastStage = delays.size() - 1;
        if (message.stageIndex() < lastStage) {
            if (shouldDiscoverExternalTrade(order)) {
                QueryAttempt attempt = queryOrUnknown(
                        order,
                        envelope.traceId(),
                        envelope.messageId(),
                        "pending_query",
                        "stage_" + message.stageIndex());
                reconciliationService.reconcilePaid(order, attempt.result());
                if (attempt.binding().tradeReferenceResolved()) {
                    // 引用绑定完成后无需继续为发现流水而逐段查询；原最终边界仍负责支付事实与过期收敛。
                    finalCheckScheduler.schedulePending(message.orderId(), order.expiresAt());
                    return;
                }
            }
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
                order,
                envelope.traceId(),
                envelope.messageId(),
                "pending_query",
                "final_boundary").result();
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

    private QueryAttempt queryOrUnknown(
            MembershipOrderSnapshot order,
            String traceId,
            String messageId,
            String trigger,
            String stage) {
        PaymentProviderType providerType = null;
        PaymentQueryResult result;
        try {
            if (order.providerTradeNo() == null) {
                PaymentQueryResult discovered = null;
                PaymentProviderType discoveredProvider = null;
                boolean unknownSeen = false;
                for (PaymentProviderType candidate : properties.publicProviders()) {
                    if (!providerEnabled(candidate)) {
                        continue;
                    }
                    try {
                        metrics.paymentQuery();
                        PaymentQueryResult candidateResult = providerRegistry
                                .getRequired(candidate)
                                .queryPayment(new PaymentQueryCommand(order.orderId(), null));
                        if (candidateResult != null
                                && PaymentProviderReference.isTrade(
                                        candidate, candidateResult.providerTradeNo())) {
                            if (discovered != null) {
                                throw providerTradeConflict();
                            }
                            discovered = candidateResult;
                            discoveredProvider = candidate;
                        } else {
                            unknownSeen = true;
                        }
                    } catch (RuntimeException exception) {
                        if (exception instanceof MembershipPaymentException paymentException
                                && paymentException.code()
                                        == MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT) {
                            throw paymentException;
                        }
                        // 单边网络失败、验签失败或响应异常都不是 NOT_FOUND；继续查询另一边，但最终保持 UNKNOWN。
                        unknownSeen = true;
                    }
                }
                if (discovered == null || unknownSeen) {
                    throw new MembershipPaymentException(
                            MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                            "No external provider returned a verifiable trade.");
                }
                providerType = discoveredProvider;
                result = discovered;
            } else {
                providerType = PaymentProviderReference.resolveTrade(
                        order.providerTradeNo());
                metrics.paymentQuery();
                MembershipPaymentProvider provider = providerRegistry.getRequired(providerType);
                result = provider.queryPayment(new PaymentQueryCommand(
                        order.orderId(), order.providerTradeNo()));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership payment final provider query was UNKNOWN; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    failureReason(exception));
            MembershipPaymentLifecycleDiagnostics.referenceResolution(
                    order,
                    providerType,
                    trigger,
                    stage,
                    "failed",
                    PaymentProviderStatus.UNKNOWN,
                    false,
                    "not_attempted",
                    "not_attempted",
                    "retry_query",
                    "PROVIDER_QUERY_FAILED",
                    traceId,
                    messageId);
            return new QueryAttempt(
                    PaymentQueryResult.unknown(order.orderId()),
                    TradeBindingOutcome.notAttempted());
        }

        TradeBindingOutcome binding;
        try {
            binding = bindResolvedProviderTradeNo(
                    order,
                    result.providerTradeNo(),
                    trigger,
                    stage,
                    traceId,
                    messageId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Membership payment provider reference binding was not completed; "
                            + "traceId={} messageId={} reason={}",
                    traceId,
                    messageId,
                    failureReason(exception));
            return new QueryAttempt(
                    PaymentQueryResult.unknown(order.orderId()),
                    TradeBindingOutcome.notAttempted());
        }
        boolean tradeNoPresent = PaymentProviderReference.rawTradeNo(
                result.providerTradeNo()) != null;
        String reason = binding.tradeReferenceResolved()
                ? "REFERENCE_BOUND_SUCCESSFULLY"
                : order.providerTradeNo() == null
                        ? "PROVIDER_RESPONSE_MISSING_TRADE_NO"
                        : "REFERENCE_ALREADY_TRADE";
        String nextAction = result.status() == PaymentProviderStatus.PAID
                ? "reconcile_paid"
                : binding.tradeReferenceResolved() ? "stop" : "retry_query";
        MembershipPaymentLifecycleDiagnostics.referenceResolution(
                order,
                providerType,
                trigger,
                stage,
                "success",
                result.status(),
                tradeNoPresent,
                binding.databaseBind(),
                binding.redisBind(),
                nextAction,
                reason,
                traceId,
                messageId);
        return new QueryAttempt(result, binding);
    }

    /** 交易号为空时仅允许唯一 Provider 的可信查询结果完成 NULL 到真实 TRADE 的原子绑定。 */
    private TradeBindingOutcome bindResolvedProviderTradeNo(
            MembershipOrderSnapshot order,
            String resolvedReference,
            String source,
            String stage,
            String traceId,
            String messageId) {
        String currentReference = order.providerTradeNo();
        if (resolvedReference == null) {
            return TradeBindingOutcome.notAttempted();
        }
        PaymentProviderType resolvedProvider = PaymentProviderReference.resolveTrade(
                resolvedReference);
        if (currentReference != null) {
            if (!currentReference.equals(resolvedReference)) {
                throw providerTradeConflict();
            }
            return TradeBindingOutcome.notAttempted();
        }
        if (!properties.publicProviders().contains(resolvedProvider)) {
            throw providerTradeConflict();
        }
        try {
            transactionService.bindProviderTradeNo(
                    order.loginIdentityId(),
                    base64UrlCodec.decode(order.orderId()),
                    resolvedReference);
        } catch (RuntimeException exception) {
            MembershipPaymentLifecycleDiagnostics.referenceResolution(
                    order,
                    resolvedProvider,
                    source,
                    stage,
                    "success",
                    PaymentProviderStatus.UNKNOWN,
                    true,
                    "failed",
                    "not_attempted",
                    "retry_query",
                    "DATABASE_BIND_FAILED",
                    traceId,
                    messageId);
            throw exception;
        }
        MembershipProviderTradeNoPatchOutcome outcome = orderStore.patchProviderTradeNo(
                order.orderId(), order.loginIdentityId(), resolvedReference);
        if (outcome == MembershipProviderTradeNoPatchOutcome.CONFLICT) {
            MembershipPaymentLifecycleDiagnostics.referenceResolution(
                    order,
                    resolvedProvider,
                    source,
                    stage,
                    "success",
                    PaymentProviderStatus.UNKNOWN,
                    true,
                    "applied",
                    "conflict",
                    "retry_query",
                    "REDIS_BIND_CONFLICT",
                    traceId,
                    messageId);
            throw providerTradeConflict();
        }
        Objects.requireNonNull(outcome, "Provider trade number patch outcome must not be null");
        if (outcome == MembershipProviderTradeNoPatchOutcome.MISSING) {
            MembershipPaymentLifecycleDiagnostics.referenceResolution(
                    order,
                    resolvedProvider,
                    source,
                    stage,
                    "success",
                    PaymentProviderStatus.UNKNOWN,
                    true,
                    "applied",
                    "missing",
                    "bind_trade",
                    "REDIS_BIND_MISSING",
                    traceId,
                    messageId);
        }
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
        return new TradeBindingOutcome("applied", redisBind, true);
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

    private boolean shouldDiscoverExternalTrade(MembershipOrderSnapshot order) {
        return order.paymentStartedAt() != null
                && order.providerTradeNo() == null;
    }

    private boolean providerEnabled(PaymentProviderType provider) {
        return switch (provider) {
            case BAR -> properties.bar().enabled();
            case LIUHAO -> properties.liuhao().enabled();
            case LOCAL_SIMULATOR -> false;
        };
    }

    private void logCallbackInProgress(
            MembershipOrderSnapshot order,
            MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope,
            int stage) {
        MembershipPaymentLifecycleDiagnostics.referenceResolution(
                order,
                PaymentProviderReference.tryResolveTrade(order.providerTradeNo()),
                "pending_query",
                "stage_" + stage,
                "skipped",
                PaymentProviderStatus.UNKNOWN,
                PaymentProviderReference.rawTradeNo(order.providerTradeNo()) != null,
                "not_attempted",
                "not_attempted",
                "stop",
                "CALLBACK_IN_PROGRESS",
                envelope.traceId(),
                envelope.messageId());
    }

    private record QueryAttempt(
            PaymentQueryResult result,
            TradeBindingOutcome binding) {
    }

    private record TradeBindingOutcome(
            String databaseBind,
            String redisBind,
            boolean tradeReferenceResolved) {

        private static TradeBindingOutcome notAttempted() {
            return new TradeBindingOutcome("not_attempted", "not_attempted", false);
        }
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
