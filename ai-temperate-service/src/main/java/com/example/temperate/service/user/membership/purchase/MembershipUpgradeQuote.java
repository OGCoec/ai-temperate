package com.example.temperate.service.user.membership.purchase;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该结果是来返回个人套餐升级的目标整月价、实际抵扣、最终应付金额和自然日依据。
 */
public record MembershipUpgradeQuote(
        MembershipTier currentTier,
        MembershipTier targetTier,
        BigDecimal targetPlanPriceYuan,
        BigDecimal creditAmountYuan,
        BigDecimal payAmountYuan,
        long subscriptionDays,
        long remainingDays,
        OffsetDateTime quotedAt) {

    public MembershipUpgradeQuote {
        Objects.requireNonNull(currentTier, "Current membership tier is required.");
        Objects.requireNonNull(targetTier, "Target membership tier is required.");
        Objects.requireNonNull(targetPlanPriceYuan, "Target plan price is required.");
        Objects.requireNonNull(creditAmountYuan, "Credit amount is required.");
        Objects.requireNonNull(payAmountYuan, "Pay amount is required.");
        Objects.requireNonNull(quotedAt, "Quote time is required.");
        if (targetPlanPriceYuan.signum() < 0
                || creditAmountYuan.signum() < 0
                || payAmountYuan.signum() < 0) {
            throw new IllegalArgumentException("Membership quote amounts must be non-negative.");
        }
        if (subscriptionDays <= 0L
                || remainingDays <= 0L
                || remainingDays > subscriptionDays) {
            throw new IllegalArgumentException("Membership quote day counts are invalid.");
        }
    }
}
