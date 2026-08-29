package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderRealtimeGuard;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptDatabaseResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来先提交 PostgreSQL 支付发起事实，再以单调版本刷新 Redis，并将并发产生的更新后快照返回客户端。
 *
 * <p>事务提交后才调用当前 Provider 创建模拟支付入口；短时提交描述只随本次结果返回，不写数据库、Redis 或日志。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentAttemptServiceImpl
        implements MembershipPaymentAttemptService {

    private final MembershipPaymentAttemptTransactionService transactionService;
    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentFactReconciliationService reconciliationService;
    private final MembershipPaymentProperties properties;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public MembershipPaymentAttemptServiceImpl(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentFactReconciliationService reconciliationService,
            MembershipPaymentProperties properties,
            HybridBase64UrlCodec base64UrlCodec,
            Clock clock) {
        this.transactionService = Objects.requireNonNull(transactionService);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.snapshotWriteCoordinator = Objects.requireNonNull(snapshotWriteCoordinator);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.properties = Objects.requireNonNull(properties);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 数据库事务返回即代表发起事实已提交；Redis 只接受更高版本，不能用旧快照覆盖并发回调状态。 */
    @Override
    public MembershipPaymentAttemptResult start(long loginIdentityId, byte[] orderId) {
        if (!properties.checkoutEnabled()) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_CHECKOUT_DISABLED,
                    "Membership payment checkout is temporarily disabled.");
        }
        if (loginIdentityId <= 0L || orderId == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.INPUT_INVALID,
                    "The current login identity or membership order is invalid.");
        }
        OffsetDateTime attemptedAt = MembershipPaymentTime.now(clock);
        String publicOrderId = base64UrlCodec.encode(orderId);
        try {
            // 取消和回调先在 Redis 形成实时状态；终态已明确时必须在 PostgreSQL 条件 UPDATE 前拒绝，
            // 否则数据库会产生与 Redis 终态相同版本的 PENDING 事实，后续检查消息可能把订单复活。
            snapshotStore.findRealtimeGuard(publicOrderId).ifPresent(snapshot ->
                    requirePaymentStartAllowed(snapshot, loginIdentityId, attemptedAt));
        } catch (MembershipPaymentInfrastructureException ignored) {
            // Redis 暂时不可读时仍让 PostgreSQL 作最终归属和状态裁决；提交后的缓存刷新会返回受控 503。
        }
        MembershipPaymentAttemptDatabaseResult databaseResult = transactionService.startOrGet(
                loginIdentityId,
                orderId.clone(),
                attemptedAt);
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(databaseResult.order());
        try {
            // 数据库事实提交后用单条 Lua 写入并返回实时快照，移除原 put 后的第二次 Redis 网络往返。
            MembershipOrderSnapshot current =
                    snapshotWriteCoordinator.patchPaymentAttempt(databaseSnapshot);
            // 回调状态先在 Redis 原子迁移、再异步批量入库；数据库短暂仍为 PENDING 时，必须以更高版本的
            // Redis 终态拒绝重放，不能把已 PAID/CANCELLED/CLOSED 的订单误报为可继续发起支付。
            requirePaymentStartAllowed(current, loginIdentityId, attemptedAt);
            requirePaymentPatchConsistent(databaseSnapshot, current);
            PaymentProviderType providerType = properties.defaultProvider();
            MembershipPaymentProvider provider = providerRegistry.getRequired(providerType);
            PaymentCheckoutResult checkout = provider.createCheckout(
                    new PaymentCheckoutCommand(
                            current.orderId(),
                            current.payAmountYuan(),
                            current.payType(),
                            "会员模拟支付订单"));
            boolean providerBindingConflict = false;
            if (checkout.providerTradeNo() != null) {
                MembershipOrder bound = transactionService.bindProviderTradeNo(
                        loginIdentityId,
                        orderId.clone(),
                        checkout.providerTradeNo());
                MembershipProviderTradeNoPatchOutcome patchOutcome =
                        snapshotStore.patchProviderTradeNo(
                                current.orderId(),
                                loginIdentityId,
                                checkout.providerTradeNo());
                if (patchOutcome == MembershipProviderTradeNoPatchOutcome.MISSING) {
                    // 数据库已完成绑定但 Redis Key 恰好丢失时，才使用完整快照恢复；版本更高的并发终态仍会胜出。
                    current = snapshotWriteCoordinator.putAndGet(toSnapshot(bound));
                } else if (patchOutcome == MembershipProviderTradeNoPatchOutcome.APPLIED
                        || patchOutcome == MembershipProviderTradeNoPatchOutcome.UNCHANGED) {
                    current = withProviderTradeNo(current, checkout.providerTradeNo());
                } else if (patchOutcome == MembershipProviderTradeNoPatchOutcome.CONFLICT) {
                    // 数据库绑定成功后 Redis 若已出现不同事实，必须先关闭刚创建的支付入口；
                    // 不能因为订单仍是 PENDING 就继续向客户端暴露一个与实时快照冲突的 Provider 交易号。
                    providerBindingConflict = true;
                }
            }

            // Provider 调用后必须从 Redis 精简 Guard 重新核验；此处失败不能用调用前快照冒充实时状态。
            MembershipOrderRealtimeGuard afterCheckout = snapshotStore
                    .findRealtimeGuard(current.orderId())
                    .orElseThrow(() -> new MembershipPaymentInfrastructureException(
                            "Redis membership order realtime guard is missing after provider checkout."));
            OffsetDateTime revalidatedAt = MembershipPaymentTime.now(clock);
            if (providerBindingConflict
                    || afterCheckout.loginIdentityId() != loginIdentityId
                    || afterCheckout.status() != MembershipOrderStatus.PENDING_PAYMENT
                    || !revalidatedAt.isBefore(afterCheckout.expiresAt())) {
                PaymentCloseResult close = provider.closePayment(new PaymentCloseCommand(
                        afterCheckout.orderId(), checkout.providerTradeNo()));
                if (close.status() == PaymentProviderStatus.PAID) {
                    reconciliationService.reconcilePaid(
                            current,
                            provider.queryPayment(new PaymentQueryCommand(
                                    afterCheckout.orderId(), checkout.providerTradeNo())));
                }
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                        "The membership order no longer allows payment to start.");
            }
            PaymentCheckoutSubmission browserSubmission = boundToOrderExpiry(
                    checkout.checkoutSubmission(), afterCheckout.expiresAt());
            return new MembershipPaymentAttemptResult(
                    current,
                    databaseResult.started(),
                    providerType,
                    browserSubmission);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Membership payment state is temporarily unavailable.",
                    exception);
        }
    }

    private static PaymentCheckoutSubmission boundToOrderExpiry(
            PaymentCheckoutSubmission submission,
            OffsetDateTime orderExpiresAt) {
        if (submission == null
                || !submission.submitExpiresAt().isAfter(orderExpiresAt)) {
            return submission;
        }

        // 本地订单截止时间是浏览器能否继续付款的最终业务边界；
        // 只缩短提交描述元数据，绝不改写已经参与 BAR 签名的表单字段。
        return new PaymentCheckoutSubmission(
                submission.provider(),
                submission.action(),
                submission.method(),
                submission.contentType(),
                orderExpiresAt,
                submission.fields());
    }

    private static void requirePaymentStartAllowed(
            MembershipOrderSnapshot snapshot,
            long loginIdentityId,
            OffsetDateTime attemptedAt) {
        if (snapshot.loginIdentityId() != loginIdentityId) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                    "The membership order was not found.");
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT
                || !attemptedAt.isBefore(snapshot.expiresAt())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The membership order no longer allows payment to start.");
        }
    }

    private static void requirePaymentStartAllowed(
            MembershipOrderRealtimeGuard snapshot,
            long loginIdentityId,
            OffsetDateTime attemptedAt) {
        if (snapshot.loginIdentityId() != loginIdentityId) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                    "The membership order was not found.");
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT
                || !attemptedAt.isBefore(snapshot.expiresAt())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The membership order no longer allows payment to start.");
        }
    }

    private static MembershipOrderSnapshot withProviderTradeNo(
            MembershipOrderSnapshot snapshot,
            String providerTradeNo) {
        return new MembershipOrderSnapshot(
                snapshot.schemaVersion(),
                snapshot.orderId(),
                snapshot.loginIdentityId(),
                snapshot.membershipTier(),
                snapshot.payAmountYuan(),
                snapshot.payType(),
                snapshot.status(),
                snapshot.idempotencyKey(),
                providerTradeNo,
                snapshot.paymentStartedAt(),
                snapshot.expiresAt(),
                snapshot.closingDeadlineAt(),
                snapshot.paidAt(),
                snapshot.stateVersion(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }

    private static void requirePaymentPatchConsistent(
            MembershipOrderSnapshot databaseSnapshot,
            MembershipOrderSnapshot current) {
        if (current.stateVersion() < databaseSnapshot.stateVersion()
                || (current.stateVersion() == databaseSnapshot.stateVersion()
                    && !Objects.equals(
                            current.paymentStartedAt(), databaseSnapshot.paymentStartedAt()))) {
            throw new MembershipPaymentInfrastructureException(
                    "Redis membership payment attempt patch did not converge to the database fact.");
        }
    }

    private MembershipOrderSnapshot toSnapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(order.getId()),
                required(order.getLoginIdentityId(), "owner"),
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
                required(order.getStateVersion(), "state version"),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static long required(Long value, String name) {
        if (value == null || value <= 0L) {
            throw new IllegalStateException("Membership order " + name + " is invalid.");
        }
        return value;
    }
}
