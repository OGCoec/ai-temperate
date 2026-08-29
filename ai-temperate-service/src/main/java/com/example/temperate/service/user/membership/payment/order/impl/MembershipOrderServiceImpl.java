package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreateCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreationTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来编排会员订单创建、Redis 优先所有权查询和原子取消，并在数据库已提交后恢复缓存与最终检查消息。
 *
 * <p>本实现不修改会员权益、不处理支付回调，也不直接更新订单终态；实时状态迁移和批量刷盘由独立组件负责。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderServiceImpl implements MembershipOrderService {

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
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private final MembershipPaymentProviderRegistry providerRegistry;
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
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec base64UrlCodec,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            MembershipPaymentProviderRegistry providerRegistry,
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
        this.idWorker = Objects.requireNonNull(idWorker);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.snapshotWriteCoordinator = Objects.requireNonNull(snapshotWriteCoordinator);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.finalCheckScheduler = Objects.requireNonNull(finalCheckScheduler);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在报价前惰性处理会员到期，先提交数据库幂等订单，再执行允许重试的 Redis 和 RabbitMQ 后置动作。
     */
    @Override
    public MembershipOrderResult create(
            long loginIdentityId,
            MembershipOrderCreateCommand command) {
        requireUserId(loginIdentityId);
        MembershipOrderCreateCommand valid = requireCreateCommand(command);
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
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(databaseResult.order());
        if (databaseSnapshot.status().terminal()) {
            return new MembershipOrderResult(databaseSnapshot, databaseResult.created());
        }

        MembershipOrderSnapshot currentSnapshot = restoreRealtimeState(databaseSnapshot);
        if (currentSnapshot.status() == MembershipOrderStatus.PENDING_PAYMENT) {
            initializeProvider(currentSnapshot);
            publishFinalPaymentCheck(currentSnapshot);
        }
        return new MembershipOrderResult(currentSnapshot, databaseResult.created());
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
     * 用户取消只允许 PENDING_PAYMENT；Lua 同时检查回调 marker 并写入 dirty 版本，数据库由五秒刷盘任务更新。
     */
    @Override
    public MembershipOrderResult cancel(long loginIdentityId, byte[] orderId) {
        MembershipOrderSnapshot snapshot = getOwned(loginIdentityId, orderId).snapshot();
        if (snapshot.status() == MembershipOrderStatus.CANCELLED) {
            return new MembershipOrderResult(snapshot, false);
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT) {
            throw stateConflict("Only pending membership orders can be cancelled.");
        }

        OffsetDateTime changedAt = now();
        MembershipOrderTransitionResult transition;
        try {
            transition = snapshotStore.cancel(snapshot.orderId(), changedAt);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw redisUnavailable(exception);
        }
        if (transition.outcome() == MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS) {
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
        return new MembershipOrderResult(
                cancelledSnapshot(snapshot, transition.stateVersion(), changedAt),
                false);
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

    private void initializeProvider(MembershipOrderSnapshot snapshot) {
        try {
            // 当前环境只能启用一个 Provider；订单创建仅初始化本地事实，BAR 实现必须保持无网络副作用。
            MembershipPaymentProvider provider = providerRegistry.getRequired(
                    properties.defaultProvider());
            provider.initializeOrder(new PaymentProviderInitializeCommand(
                    snapshot.orderId(), snapshot.createdAt()));
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
