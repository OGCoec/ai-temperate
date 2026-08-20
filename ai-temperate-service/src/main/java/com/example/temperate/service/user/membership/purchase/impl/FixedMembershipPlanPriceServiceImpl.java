package com.example.temperate.service.user.membership.purchase.impl;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来以内置不可变目录提供当前测试环境的六档付费会员价格。
 *
 * <p>价格只作为报价事实来源，不负责订单创建、收款或会员权益更新。</p>
 */
@Service
public final class FixedMembershipPlanPriceServiceImpl
        implements MembershipPlanPriceService {

    private static final Map<MembershipTier, BigDecimal> PRICES = prices();

    @Override
    public BigDecimal getRequiredPrice(MembershipTier membershipTier) {
        MembershipTier requiredTier = Objects.requireNonNull(
                membershipTier, "Membership tier is required.");
        BigDecimal price = PRICES.get(requiredTier);
        if (price == null) {
            throw new IllegalArgumentException(
                    "Membership tier is not purchasable: " + requiredTier);
        }
        return price;
    }

    private static Map<MembershipTier, BigDecimal> prices() {
        EnumMap<MembershipTier, BigDecimal> prices =
                new EnumMap<>(MembershipTier.class);
        prices.put(MembershipTier.GO, new BigDecimal("0.05"));
        prices.put(MembershipTier.EDU, new BigDecimal("0.10"));
        prices.put(MembershipTier.TEAM, new BigDecimal("0.10"));
        prices.put(MembershipTier.PLUS, new BigDecimal("0.20"));
        prices.put(MembershipTier.PRO, new BigDecimal("0.30"));
        prices.put(MembershipTier.MAX, new BigDecimal("0.50"));
        return Map.copyOf(prices);
    }
}
