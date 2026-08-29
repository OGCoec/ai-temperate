package com.example.temperate.service.user.membership.payment.offer;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 该结果是来一次返回当前有效套餐、支付环境和全部允许购买的个人套餐报价。
 */
public record MembershipPlanOfferResult(
        MembershipTier currentTier,
        PaymentProviderType provider,
        boolean checkoutEnabled,
        OffsetDateTime quotedAt,
        List<String> payTypes,
        List<MembershipPlanOffer> offers) {

    public MembershipPlanOfferResult {
        Objects.requireNonNull(currentTier, "Current membership tier is required.");
        Objects.requireNonNull(provider, "Payment provider is required.");
        Objects.requireNonNull(quotedAt, "Quote time is required.");
        payTypes = List.copyOf(Objects.requireNonNull(payTypes, "Payment types are required."));
        offers = List.copyOf(Objects.requireNonNull(offers, "Membership offers are required."));
    }
}
