package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptDatabaseResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来先提交 PostgreSQL 支付发起事实，再以单调版本刷新 Redis，并将并发产生的更新后快照返回客户端。
 *
 * <p>它不创建真实支付、支付跳转或会员权益，只记录状态机判断迟到成功所需的可信发起时间。</p>
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
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public MembershipPaymentAttemptServiceImpl(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            HybridBase64UrlCodec base64UrlCodec,
            Clock clock) {
        this.transactionService = Objects.requireNonNull(transactionService);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 数据库事务返回即代表发起事实已提交；Redis 只接受更高版本，不能用旧快照覆盖并发回调状态。 */
    @Override
    public MembershipPaymentAttemptResult start(long loginIdentityId, byte[] orderId) {
        if (loginIdentityId <= 0L || orderId == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.INPUT_INVALID,
                    "The current login identity or membership order is invalid.");
        }
        OffsetDateTime attemptedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String publicOrderId = base64UrlCodec.encode(orderId);
        try {
            // 取消和回调先在 Redis 形成实时状态；终态已明确时必须在 PostgreSQL 条件 UPDATE 前拒绝，
            // 否则数据库会产生与 Redis 终态相同版本的 PENDING 事实，后续检查消息可能把订单复活。
            snapshotStore.find(publicOrderId).ifPresent(snapshot ->
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
            snapshotStore.put(databaseSnapshot);
            MembershipOrderSnapshot current = snapshotStore
                    .find(databaseSnapshot.orderId())
                    .orElse(databaseSnapshot);
            // 回调状态先在 Redis 原子迁移、再异步批量入库；数据库短暂仍为 PENDING 时，必须以更高版本的
            // Redis 终态拒绝重放，不能把已 PAID/CANCELLED/CLOSED 的订单误报为可继续发起支付。
            requirePaymentStartAllowed(current, loginIdentityId, attemptedAt);
            return new MembershipPaymentAttemptResult(current, databaseResult.started());
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Membership payment state is temporarily unavailable.",
                    exception);
        }
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
