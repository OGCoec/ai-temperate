package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreateCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationLockService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderReplacementCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipSupersededClosePublisher;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionCommand;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionDecision;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteCommand;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来编排会员订单创建、不同幂等键强制替换、Redis 优先所有权查询和取消状态机，并在数据库提交后恢复缓存与检查消息。
 *
 * <p>本实现不发放会员权益、不处理支付回调；显式取消沿用 CLOSING 确认链，强制替换则先形成不可发权益终态，第三方旧单由独立消费者异步关单。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderServiceImpl implements MembershipOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MembershipOrderServiceImpl.class);
    private static final Set<String> SUPPORTED_PAY_TYPES = Set.of("alipay", "wxpay");
    private static final Set<MembershipTier> PERSONAL_PURCHASABLE_TIERS = Set.of(
            MembershipTier.GO,
            MembershipTier.PLUS,
            MembershipTier.PRO,
            MembershipTier.MAX);

    private final MembershipExpirationService expirationService;
    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipTransitionPolicy transitionPolicy;
    private final MembershipPlanPriceService priceService;
    private final MembershipUpgradeQuoteService upgradeQuoteService;
    private final MembershipOrderMapper orderMapper;
    private final MembershipOrderCreationTransactionService creationTransactionService;
    private final MembershipOrderCreationLockService creationLockService;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final MembershipClosingCheckPublisher closingPublisher;
    private final MembershipSupersededClosePublisher supersededClosePublisher;
    private final MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private final MembershipPaymentProperties properties;
    private final Clock clock;

    public MembershipOrderServiceImpl(
            MembershipExpirationService expirationService,
            UserMembershipQuotaMapper quotaMapper,
            MembershipTransitionPolicy transitionPolicy,
            MembershipPlanPriceService priceService,
            MembershipUpgradeQuoteService upgradeQuoteService,
            MembershipOrderMapper orderMapper,
            MembershipOrderCreationTransactionService creationTransactionService,
            MembershipOrderCreationLockService creationLockService,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            MembershipPaymentProviderRegistry providerRegistry,
            MembershipClosingCheckPublisher closingPublisher,
            MembershipSupersededClosePublisher supersededClosePublisher,
            MembershipPaymentFinalCheckScheduler finalCheckScheduler,
            MembershipPaymentProperties properties,
            Clock clock) {
        this.expirationService = Objects.requireNonNull(expirationService);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.transitionPolicy = Objects.requireNonNull(transitionPolicy);
        this.priceService = Objects.requireNonNull(priceService);
        this.upgradeQuoteService = Objects.requireNonNull(upgradeQuoteService);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.creationTransactionService = Objects.requireNonNull(creationTransactionService);
        this.creationLockService = Objects.requireNonNull(creationLockService);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.snapshotWriteCoordinator = Objects.requireNonNull(snapshotWriteCoordinator);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.closingPublisher = Objects.requireNonNull(closingPublisher);
        this.supersededClosePublisher = Objects.requireNonNull(supersededClosePublisher);
        this.finalCheckScheduler = Objects.requireNonNull(finalCheckScheduler);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在报价前惰性处理会员到期；普通创建先提交数据库，强制替换则由 Redis marker 裁决后在单个 PostgreSQL 事务中替换活动订单。
     */
    @Override
    public MembershipOrderResult create(
            long loginIdentityId,
            MembershipOrderCreateCommand command) {
        requireUserId(loginIdentityId);
        MembershipOrderCreateCommand valid = requireCreateCommand(command);
        return creationLockService.execute(
                loginIdentityId,
                () -> createLocked(loginIdentityId, valid));
    }

    /**
     * 用户级锁内先解析幂等与旧活动订单；只有不同幂等键才执行 Redis 终结和 PostgreSQL 原子替换。
     */
    private MembershipOrderResult createLocked(
            long loginIdentityId,
            MembershipOrderCreateCommand valid) {
        expirationService.expireIfDue(loginIdentityId);
        UserMembershipQuota quota = requireQuota(loginIdentityId);
        MembershipTier currentTier = resolveTier(quota.getMembershipTier());
        MembershipTransitionDecision transition = transitionPolicy.evaluate(
                new MembershipTransitionCommand(
                        currentTier,
                        valid.targetTier(),
                        quota.getMembershipExpiresAt()));
        if (transition.transitionType() == MembershipTransitionType.REJECTED) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_TRANSITION_REJECTED,
                    "The requested membership transition is not allowed.");
        }

        OffsetDateTime now = now();
        BigDecimal payAmount = quoteAmount(loginIdentityId, quota, transition);
        MembershipOrder candidate = newOrder(loginIdentityId, valid, payAmount, now);
        MembershipOrderCreationResult databaseResult =
                creationTransactionService.createOrGet(candidate);
        if (databaseResult.replacementRequired()) {
            return replaceActiveOrder(candidate, databaseResult.order(), now);
        }
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(databaseResult.order());
        if (databaseSnapshot.status().terminal()) {
            return new MembershipOrderResult(databaseSnapshot, databaseResult.created());
        }

        MembershipOrderSnapshot currentSnapshot = restoreRealtimeState(databaseSnapshot);
        if (currentSnapshot.status() == MembershipOrderStatus.PENDING_PAYMENT) {
            publishFinalPaymentCheck(currentSnapshot);
        }
        return new MembershipOrderResult(currentSnapshot, databaseResult.created());
    }

    private MembershipOrderResult replaceActiveOrder(
            MembershipOrder candidate,
            MembershipOrder active,
            OffsetDateTime changedAt) {
        MembershipOrderSnapshot oldDatabaseSnapshot = toSnapshot(active);
        MembershipOrderSnapshot oldRealtimeSnapshot = restoreRealtimeState(oldDatabaseSnapshot);
        requireOwner(requiredLong(candidate.getLoginIdentityId(), "candidate owner"),
                oldRealtimeSnapshot);
        boolean externalPaymentStarted = active.getStatus() == MembershipOrderStatus.CLOSING
                || active.getPaymentStartedAt() != null
                || active.getProviderTradeNo() != null
                || oldRealtimeSnapshot.status() == MembershipOrderStatus.CLOSING
                || oldRealtimeSnapshot.paymentStartedAt() != null
                || oldRealtimeSnapshot.providerTradeNo() != null;

        MembershipOrderTransitionResult transition;
        try {
            transition = snapshotStore.supersedeForReplacement(
                    oldRealtimeSnapshot.orderId(),
                    externalPaymentStarted,
                    changedAt);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS) {
            logReplacement(
                    active,
                    externalPaymentStarted,
                    "not_replaced",
                    "callback_in_progress",
                    false);
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                    "A payment callback is being processed for the replaced order.");
        }
        if (transition.outcome() != MembershipOrderTransitionOutcome.APPLIED
                && transition.outcome() != MembershipOrderTransitionOutcome.ALREADY_APPLIED) {
            logReplacement(
                    active,
                    externalPaymentStarted,
                    "not_replaced",
                    transition.outcome().name().toLowerCase(Locale.ROOT),
                    false);
            throw stateConflict(
                    "The active membership order cannot be replaced during settlement.");
        }
        if (transition.status() != MembershipOrderStatus.CANCELLED
                && transition.status() != MembershipOrderStatus.CLOSED) {
            throw stateConflict("The replaced membership order did not reach a terminal state.");
        }

        MembershipOrderCreationResult replacement = creationTransactionService.replaceActive(
                new MembershipOrderReplacementCommand(
                        candidate,
                        active.getId(),
                        transition.status(),
                        transition.stateVersion(),
                        changedAt));
        MembershipOrderSnapshot newDatabaseSnapshot = toSnapshot(replacement.order());
        MembershipOrderSnapshot current = newDatabaseSnapshot.status().terminal()
                ? newDatabaseSnapshot
                : restoreRealtimeState(newDatabaseSnapshot);
        // 旧单第三方关单是替换动作的直接补偿，必须先尝试发布，避免新单检查消息异常阻断旧单补偿链。
        if (transition.status() == MembershipOrderStatus.CLOSED) {
            publishSupersededClose(oldRealtimeSnapshot);
        }
        logReplacement(
                active,
                externalPaymentStarted,
                replacement.created() ? "replaced" : "replayed",
                transition.status().name().toLowerCase(Locale.ROOT),
                replacement.created());
        if (current.status() == MembershipOrderStatus.PENDING_PAYMENT) {
            publishFinalPaymentCheck(current);
        }
        return new MembershipOrderResult(current, replacement.created());
    }

    /** 第三方旧单关单是替换后的补偿动作；发布失败不能让已经创建的新订单对用户消失。 */
    private void publishSupersededClose(MembershipOrderSnapshot oldOrder) {
        try {
            supersededClosePublisher.publish(oldOrder.orderId(), 0, Duration.ZERO);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=membership_order_superseded_close_publish_failed "
                            + "provider_trade_present={} traceId={} cause={}",
                    oldOrder.providerTradeNo() != null,
                    MembershipPaymentTraceContext.currentTraceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private static void logReplacement(
            MembershipOrder oldOrder,
            boolean paymentStarted,
            String databaseOutcome,
            String redisTransition,
            boolean newOrderCreated) {
        LOGGER.info(
                "event=membership_order_force_replacement old_status={} "
                        + "payment_started={} redis_transition={} database_outcome={} "
                        + "new_order_created={} traceId={}",
                oldOrder.getStatus().name().toLowerCase(Locale.ROOT),
                paymentStarted,
                redisTransition,
                databaseOutcome,
                newOrderCreated,
                MembershipPaymentTraceContext.currentTraceId());
    }

    /**
     * Redis 命中时以实时快照为准；仅在缺失时回退数据库，并且只重建可能继续迁移的非终态订单。
     */
    @Override
    public MembershipOrderResult getOwned(long loginIdentityId, byte[] orderId) {
        requireUserId(loginIdentityId);
        String publicOrderId = base64UrlCodec.encode(orderId);
        Optional<MembershipOrderSnapshot> cached = findSnapshot(publicOrderId);
        if (cached.isPresent()) {
            MembershipOrderSnapshot snapshot = cached.orElseThrow();
            requireOwner(loginIdentityId, snapshot);
            return new MembershipOrderResult(snapshot, false);
        }

        MembershipOrder persisted = orderMapper.findOwnedById(orderId, loginIdentityId);
        if (persisted == null) {
            throw notFound();
        }
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(persisted);
        if (databaseSnapshot.status().terminal()) {
            return new MembershipOrderResult(databaseSnapshot, false);
        }
        return new MembershipOrderResult(restoreRealtimeState(databaseSnapshot), false);
    }

    /**
     * 未发起外部支付时允许本地取消；已发起的外部支付必须先进入 CLOSING 并由现有关单消息链确认第三方终态。
     */
    @Override
    public MembershipOrderResult cancel(long loginIdentityId, byte[] orderId) {
        MembershipOrderSnapshot snapshot = getOwned(loginIdentityId, orderId).snapshot();
        if (snapshot.status() == MembershipOrderStatus.CANCELLED) {
            return new MembershipOrderResult(snapshot, false);
        }
        if (snapshot.status() == MembershipOrderStatus.CLOSING) {
            if (callbackInProgress(snapshot.orderId())) {
                PaymentProviderType provider = resolvedTradeProvider(snapshot.providerTradeNo());
                logManualClose(
                        snapshot,
                        provider,
                        true,
                        "skipped",
                        "none",
                        "stop",
                        "CALLBACK_IN_PROGRESS");
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                        "A payment callback is being processed for this order.");
            }
            publishManualClosing(snapshot);
            return new MembershipOrderResult(snapshot, false);
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT) {
            throw stateConflict("Only pending membership orders can be cancelled.");
        }

        PaymentProviderType provider = resolvedTradeProvider(snapshot.providerTradeNo());
        if (snapshot.paymentStartedAt() != null) {
            // 真实交易号缺失时仍进入 CLOSING，由补偿消费者执行全 Provider 发现；这里绝不猜默认路由。
            return startManualClosing(snapshot, provider);
        }

        OffsetDateTime changedAt = now();
        MembershipOrderTransitionResult transition;
        try {
            transition = snapshotStore.cancel(snapshot.orderId(), changedAt);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS) {
            logManualClose(
                    snapshot,
                    provider,
                    true,
                    "skipped",
                    "none",
                    "stop",
                    "CALLBACK_IN_PROGRESS");
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                    "A payment callback is being processed for this order.");
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.MISSING) {
            throw notFound();
        }
        if (transition.outcome() != MembershipOrderTransitionOutcome.APPLIED
                && transition.outcome() != MembershipOrderTransitionOutcome.ALREADY_APPLIED) {
            throw stateConflict("The membership order state no longer allows cancellation.");
        }
        logManualClose(
                snapshot,
                provider,
                false,
                "skipped",
                "none",
                "finalize_closed",
                "PAYMENT_NEVER_STARTED");
        return new MembershipOrderResult(
                cancelledSnapshot(snapshot, transition.stateVersion(), changedAt),
                false);
    }

    private MembershipOrderResult startManualClosing(
            MembershipOrderSnapshot snapshot,
            PaymentProviderType provider) {
        OffsetDateTime changedAt = now();
        // 关单宽限期必须从订单支付截止点起算；从取消点击时刻起算会让已过期订单过早进入 CLOSED。
        OffsetDateTime closingDeadlineAt = snapshot.expiresAt().plus(properties.closingDuration());
        MembershipOrderTransitionResult transition;
        try {
            // Lua 同时检查 callback marker 与当前状态，避免手动取消越过正在收敛的支付成功事实。
            transition = snapshotStore.startClosing(
                    snapshot.orderId(),
                    closingDeadlineAt,
                    changedAt,
                    snapshot.expiresAt().plus(properties.closingDuration()));
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS) {
            logManualClose(
                    snapshot,
                    provider,
                    true,
                    "skipped",
                    "none",
                    "stop",
                    "CALLBACK_IN_PROGRESS");
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                    "A payment callback is being processed for this order.");
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.MISSING) {
            throw notFound();
        }
        if (transition.outcome() != MembershipOrderTransitionOutcome.APPLIED
                && transition.outcome() != MembershipOrderTransitionOutcome.ALREADY_APPLIED) {
            throw stateConflict("The membership order state no longer allows cancellation.");
        }
        MembershipOrderSnapshot closing = closingSnapshot(
                snapshot,
                transition.stateVersion(),
                closingDeadlineAt,
                changedAt);
        publishManualClosing(closing);
        logManualClose(
                closing,
                provider,
                false,
                "skipped",
                "pending_to_closing",
                "retry_close",
                "BEFORE_CLOSING_DEADLINE");
        return new MembershipOrderResult(closing, false);
    }

    private void publishManualClosing(MembershipOrderSnapshot snapshot) {
        try {
            closingPublisher.publishNext(snapshot.orderId(), 0, 0, Duration.ZERO);
        } catch (RuntimeException exception) {
            PaymentProviderType provider = resolvedTradeProvider(snapshot.providerTradeNo());
            logManualClose(
                    snapshot,
                    provider,
                    false,
                    "failed",
                    "none",
                    "retry_close",
                    "CLOSE_REQUEST_FAILED");
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                    "Membership payment close could not be published.",
                    exception);
        }
    }

    private static void logManualClose(
            MembershipOrderSnapshot snapshot,
            PaymentProviderType provider,
            boolean callbackMarker,
            String closeRequest,
            String transition,
            String nextAction,
            String reason) {
        MembershipPaymentLifecycleDiagnostics.closeLifecycle(
                snapshot,
                provider,
                "manual_cancel",
                callbackMarker,
                closeRequest,
                "not_available",
                "not_available",
                "not_available",
                PaymentProviderStatus.UNKNOWN,
                snapshot.providerTradeNo() != null,
                "not_required",
                transition,
                nextAction,
                reason,
                MembershipPaymentTraceContext.currentTraceId(),
                "unavailable");
    }

    private static PaymentProviderType resolvedTradeProvider(String reference) {
        try {
            return PaymentProviderReference.tryResolveTrade(reference);
        } catch (IllegalArgumentException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                    "The stored provider trade reference is invalid.",
                    exception);
        }
    }

    private MembershipOrderSnapshot restoreRealtimeState(
            MembershipOrderSnapshot databaseSnapshot) {
        try {
            // 单条 Lua 同时完成单调版本裁决并返回当前快照，数据库旧版本不会覆盖并发迁移，也不再额外 HGETALL。
            return snapshotWriteCoordinator.putAndGet(databaseSnapshot);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
    }

    private Optional<MembershipOrderSnapshot> findSnapshot(String orderId) {
        try {
            return snapshotStore.find(orderId);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
    }

    private boolean callbackInProgress(String orderId) {
        try {
            return snapshotStore.callbackInProgress(orderId);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
    }

    private void publishFinalPaymentCheck(MembershipOrderSnapshot snapshot) {
        try {
            finalCheckScheduler.schedulePending(snapshot.orderId(), snapshot.expiresAt());
        } catch (MembershipPaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_RABBIT_UNAVAILABLE,
                    "Membership payment check could not be published.",
                    exception);
        }
    }

    private BigDecimal quoteAmount(
            long loginIdentityId,
            UserMembershipQuota quota,
            MembershipTransitionDecision transition) {
        if (transition.transitionType() == MembershipTransitionType.NEW_PURCHASE) {
            return priceService.getRequiredPrice(transition.targetTier());
        }
        MembershipOrder latestPaid = orderMapper.findLatestPaidOrder(
                loginIdentityId,
                transition.effectiveCurrentTier());
        if (latestPaid == null
                || latestPaid.getPaidAt() == null
                || quota.getMembershipExpiresAt() == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_UPGRADE_HISTORY_MISSING,
                    "A trusted paid membership period is required for upgrade pricing.");
        }
        return upgradeQuoteService.quote(new MembershipUpgradeQuoteCommand(
                        transition.effectiveCurrentTier(),
                        transition.targetTier(),
                        latestPaid.getPaidAt(),
                        quota.getMembershipExpiresAt(),
                        latestPaid.getPayAmountYuan()))
                .payAmountYuan();
    }

    private MembershipOrder newOrder(
            long loginIdentityId,
            MembershipOrderCreateCommand command,
            BigDecimal payAmount,
            OffsetDateTime now) {
        MembershipOrder order = new MembershipOrder();
        order.setId(idWorker.nextId());
        order.setLoginIdentityId(loginIdentityId);
        order.setMembershipTier(command.targetTier());
        order.setPayAmountYuan(payAmount);
        order.setPayType(command.payType());
        // 本地订单创建不选择外部 Provider，也绝不把本地订单号伪装成第三方交易号。
        // 只有支付尝试成功拿到平台真实流水后，provider_trade_no 才允许从 NULL 原子绑定为 TRADE 引用。
        order.setProviderTradeNo(null);
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(command.idempotencyKey());
        order.setExpiresAt(now.plus(properties.pendingDuration()));
        order.setStateVersion(1L);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private MembershipOrderSnapshot toSnapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(order.getId()),
                requiredLong(order.getLoginIdentityId(), "order owner"),
                order.getMembershipTier(),
                order.getPayAmountYuan(),
                order.getPayType(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getProviderTradeNo(),
                order.getPaymentStartedAt(),
                order.getExpiresAt(),
                order.getClosingDeadlineAt(),
                order.getPaidAt(),
                requiredLong(order.getStateVersion(), "state version"),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static MembershipOrderSnapshot cancelledSnapshot(
            MembershipOrderSnapshot source,
            long stateVersion,
            OffsetDateTime changedAt) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(),
                source.orderId(),
                source.loginIdentityId(),
                source.membershipTier(),
                source.payAmountYuan(),
                source.payType(),
                MembershipOrderStatus.CANCELLED,
                source.idempotencyKey(),
                source.providerTradeNo(),
                source.paymentStartedAt(),
                source.expiresAt(),
                null,
                source.paidAt(),
                stateVersion,
                source.createdAt(),
                changedAt);
    }

    private static MembershipOrderSnapshot closingSnapshot(
            MembershipOrderSnapshot source,
            long stateVersion,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime changedAt) {
        return new MembershipOrderSnapshot(
                source.schemaVersion(),
                source.orderId(),
                source.loginIdentityId(),
                source.membershipTier(),
                source.payAmountYuan(),
                source.payType(),
                MembershipOrderStatus.CLOSING,
                source.idempotencyKey(),
                source.providerTradeNo(),
                source.paymentStartedAt(),
                source.expiresAt(),
                closingDeadlineAt,
                source.paidAt(),
                stateVersion,
                source.createdAt(),
                changedAt);
    }

    private static MembershipOrderCreateCommand requireCreateCommand(
            MembershipOrderCreateCommand command) {
        MembershipOrderCreateCommand value = Objects.requireNonNull(
                command, "membership order command must not be null");
        if (value.targetTier() == null
                || !PERSONAL_PURCHASABLE_TIERS.contains(value.targetTier())) {
            throw inputInvalid("A personal paid target membership tier is required.");
        }
        String payType = value.payType() == null
                ? null
                : value.payType().toLowerCase(Locale.ROOT);
        if (payType == null
                || !payType.equals(value.payType())
                || !SUPPORTED_PAY_TYPES.contains(payType)) {
            throw inputInvalid("The payment type is unsupported.");
        }
        UUID idempotencyKey = value.idempotencyKey();
        if (idempotencyKey == null
                || idempotencyKey.version() != 4
                || idempotencyKey.variant() != 2) {
            throw inputInvalid("A canonical UUIDv4 idempotency key is required.");
        }
        // 兼容期旧客户端即使携带 provider 也只被反序列化，不参与校验、订单身份或持久化；
        // 真正的公开 Provider 白名单只在 payment-attempts 边界执行。
        return value;
    }

    private UserMembershipQuota requireQuota(long loginIdentityId) {
        UserMembershipQuota quota = quotaMapper.findByLoginIdentityId(loginIdentityId);
        if (quota == null
                || quota.getLoginIdentityId() == null
                || quota.getLoginIdentityId() != loginIdentityId) {
            throw stateConflict("The current membership quota record is unavailable.");
        }
        return quota;
    }

    private static MembershipTier resolveTier(Integer code) {
        if (code == null || code < 0 || code >= MembershipTier.values().length) {
            throw stateConflict("The current membership tier is invalid.");
        }
        return MembershipTier.values()[code];
    }

    private static void requireOwner(
            long loginIdentityId,
            MembershipOrderSnapshot snapshot) {
        if (snapshot.loginIdentityId() != loginIdentityId) {
            throw notFound();
        }
    }

    private static long requiredLong(Long value, String name) {
        if (value == null || value <= 0L) {
            throw new IllegalStateException("Membership order " + name + " is invalid.");
        }
        return value;
    }

    private static void requireUserId(long loginIdentityId) {
        if (loginIdentityId <= 0L) {
            throw inputInvalid("The current login identity is invalid.");
        }
    }

    private OffsetDateTime now() {
        // 一个订单的 createdAt、updatedAt 与 expiresAt 必须从同一个微秒事实派生，
        // 避免数据库往返后边界值发生纳秒舍入漂移。
        return MembershipPaymentTime.now(clock);
    }

    private static MembershipPaymentException inputInvalid(String message) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.INPUT_INVALID,
                message);
    }

    private static MembershipPaymentException notFound() {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                "The membership order was not found.");
    }

    private static MembershipPaymentException stateConflict(String message) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                message);
    }

    private static MembershipPaymentException redisUnavailable(Throwable cause) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                "Membership payment state is temporarily unavailable.",
                cause);
    }
}
