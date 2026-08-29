package com.example.temperate.service.user.membership.payment.offer;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 该结果是来承载一档当前用户可购买的个人会员套餐及其服务端权威展示金额。
 *
 * <p>金额只用于报价展示，创建订单时仍由订单服务重新裁决，避免客户端价格成为收款依据。</p>
 */
public record MembershipPlanOffer(
        MembershipTier targetTier,
        String displayName,
        BigDecimal listPriceYuan,
        BigDecimal creditAmountYuan,
        BigDecimal payAmountYuan,
        MembershipTransitionType transitionType) {

    public MembershipPlanOffer {
        Objects.requireNonNull(targetTier, "Target membership tier is required.");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Membership offer display name is required.");
        }
        listPriceYuan = money(listPriceYuan, "List price");
        creditAmountYuan = money(creditAmountYuan, "Credit amount");
        payAmountYuan = money(payAmountYuan, "Pay amount");
        Objects.requireNonNull(transitionType, "Membership transition type is required.");
        if (transitionType != MembershipTransitionType.NEW_PURCHASE
                && transitionType != MembershipTransitionType.UPGRADE) {
            throw new IllegalArgumentException("Membership offer transition must be purchasable.");
        }
        if (creditAmountYuan.add(payAmountYuan).compareTo(listPriceYuan) != 0) {
            throw new IllegalArgumentException("Membership offer amounts do not balance.");
        }
    }

    private static BigDecimal money(BigDecimal value, String name) {
        BigDecimal required = Objects.requireNonNull(value, name + " is required.");
        if (required.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative.");
        }
        try {
            return required.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must use at most two decimals.", exception);
        }
    }
}
