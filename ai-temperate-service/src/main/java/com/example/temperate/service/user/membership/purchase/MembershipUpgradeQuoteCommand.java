package com.example.temperate.service.user.membership.purchase;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来为个人套餐升级报价提供当前订阅周期和旧套餐实际支付金额。
 *
 * <p>这些值暂由未来可信业务编排层传入；当前权益表不为本功能新增持久化字段。</p>
 */
public record MembershipUpgradeQuoteCommand(
        MembershipTier currentTier,
        MembershipTier targetTier,
        OffsetDateTime membershipStartedAt,
        OffsetDateTime membershipExpiresAt,
        BigDecimal currentPaidAmountYuan) {

    public MembershipUpgradeQuoteCommand {
        Objects.requireNonNull(currentTier, "Current membership tier is required.");
        Objects.requireNonNull(targetTier, "Target membership tier is required.");
        Objects.requireNonNull(
                membershipStartedAt, "Membership start time is required.");
        Objects.requireNonNull(
                membershipExpiresAt, "Membership expiration time is required.");
        BigDecimal requiredPaidAmount = Objects.requireNonNull(
                currentPaidAmountYuan, "Current paid amount is required.");
        if (!membershipStartedAt.isBefore(membershipExpiresAt)) {
            throw new IllegalArgumentException(
                    "Membership period must start before expiration.");
        }
        if (requiredPaidAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "Current paid amount must be non-negative.");
        }
        try {
            currentPaidAmountYuan = requiredPaidAmount.setScale(
                    2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Current paid amount must have at most two decimal places.",
                    exception);
        }
    }
}
