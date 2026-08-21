package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 该快照是来在 Redis Hash 中保存会员订单状态机所需的有界字段，并携带 PostgreSQL 单调持久化版本。
 *
 * <p>快照不包含用户密码、支付签名、买家身份或完整回调报文；订单归属仍需在服务层校验。</p>
 */
public record MembershipOrderSnapshot(
        int schemaVersion,
        String orderId,
        long loginIdentityId,
        MembershipTier membershipTier,
        BigDecimal payAmountYuan,
        String payType,
        MembershipOrderStatus status,
        UUID idempotencyKey,
        String providerTradeNo,
        OffsetDateTime paymentStartedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime closingDeadlineAt,
        OffsetDateTime paidAt,
        long stateVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public MembershipOrderSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported membership order snapshot schema.");
        }
        new MembershipOrderRedisId(orderId);
        if (loginIdentityId <= 0
                || membershipTier == null
                || membershipTier == MembershipTier.FREE) {
            throw new IllegalArgumentException("Membership order snapshot identity or tier is invalid.");
        }
        payAmountYuan = requireAmount(payAmountYuan);
        payType = requireText("pay type", payType, 16);
        status = Objects.requireNonNull(status, "status must not be null");
        idempotencyKey = Objects.requireNonNull(
                idempotencyKey, "idempotencyKey must not be null");
        providerTradeNo = optionalText("provider trade number", providerTradeNo, 128);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (paymentStartedAt != null && !paymentStartedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "Membership payment must start before the order expires.");
        }
        if (stateVersion <= 0) {
            throw new IllegalArgumentException("Membership order state version must be positive.");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * 该兼容构造器是来让不关心支付发起事实的既有状态机测试显式得到空值，避免把测试时间误当作发起时间。
     */
    public MembershipOrderSnapshot(
            int schemaVersion,
            String orderId,
            long loginIdentityId,
            MembershipTier membershipTier,
            BigDecimal payAmountYuan,
            String payType,
            MembershipOrderStatus status,
            UUID idempotencyKey,
            String providerTradeNo,
            OffsetDateTime expiresAt,
            OffsetDateTime closingDeadlineAt,
            OffsetDateTime paidAt,
            long stateVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(
                schemaVersion,
                orderId,
                loginIdentityId,
                membershipTier,
                payAmountYuan,
                payType,
                status,
                idempotencyKey,
                providerTradeNo,
                null,
                expiresAt,
                closingDeadlineAt,
                paidAt,
                stateVersion,
                createdAt,
                updatedAt);
    }

    private static BigDecimal requireAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Membership order amount must be non-negative.");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Membership order amount must contain at most two decimals.", exception);
        }
    }

    private static String requireText(String name, String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > maximumLength) {
            throw new IllegalArgumentException("Membership order " + name + " is invalid.");
        }
        return value;
    }

    private static String optionalText(String name, String value, int maximumLength) {
        return value == null ? null : requireText(name, value, maximumLength);
    }
}
