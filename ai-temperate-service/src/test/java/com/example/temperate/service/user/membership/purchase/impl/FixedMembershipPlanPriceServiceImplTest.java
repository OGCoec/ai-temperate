package com.example.temperate.service.user.membership.purchase.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定当前测试环境六档付费套餐的固定价格和 FREE 不可购买边界。
 */
final class FixedMembershipPlanPriceServiceImplTest {

    private final FixedMembershipPlanPriceServiceImpl service =
            new FixedMembershipPlanPriceServiceImpl();

    @Test
    void exposesEveryPaidTierWithAnExactTwoDecimalPrice() {
        Map<MembershipTier, BigDecimal> expected = Map.of(
                MembershipTier.GO, new BigDecimal("0.05"),
                MembershipTier.EDU, new BigDecimal("0.10"),
                MembershipTier.TEAM, new BigDecimal("0.10"),
                MembershipTier.PLUS, new BigDecimal("0.20"),
                MembershipTier.PRO, new BigDecimal("0.30"),
                MembershipTier.MAX, new BigDecimal("0.50"));

        expected.forEach((tier, price) -> {
            BigDecimal actual = service.getRequiredPrice(tier);
            assertThat(actual).isEqualByComparingTo(price);
            assertThat(actual.scale()).isEqualTo(2);
        });
    }

    @Test
    void rejectsFreeBecauseItIsNotAPurchasablePlan() {
        assertThatThrownBy(() -> service.getRequiredPrice(MembershipTier.FREE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREE");
    }
}
