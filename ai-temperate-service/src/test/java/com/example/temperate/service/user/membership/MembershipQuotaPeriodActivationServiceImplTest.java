package com.example.temperate.service.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.membership.impl.MembershipQuotaPeriodActivationServiceImpl;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束新套餐的七天额度周期只能在第一次成功预扣时启动，并与既有过期周期使用同一原子激活规则。
 */
final class MembershipQuotaPeriodActivationServiceImplTest {

    private static final OffsetDateTime FIRST_USAGE_AT =
            OffsetDateTime.parse("2026-08-22T12:00:00Z");

    @Test
    void unactivatedPaidQuotaStartsAtFirstUsageWithTheFullPlanBalance() {
        MembershipQuotaPlanService plans = mock(MembershipQuotaPlanService.class);
        when(plans.getRequired(MembershipTier.PLUS))
                .thenReturn(new MembershipQuotaPlan(200_000L, Duration.ofDays(7)));
        UserMembershipQuota quota = quota(MembershipTier.PLUS);
        quota.setQuotaBalanceMinor(200_000L);
        quota.setQuotaPeriodStartedAt(null);
        quota.setQuotaPeriodEndsAt(FIRST_USAGE_AT.minusHours(2));

        new MembershipQuotaPeriodActivationServiceImpl(plans)
                .activateIfDue(quota, FIRST_USAGE_AT);

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(200_000L);
        assertThat(quota.getQuotaPeriodStartedAt()).isEqualTo(FIRST_USAGE_AT);
        assertThat(quota.getQuotaPeriodEndsAt())
                .isEqualTo(FIRST_USAGE_AT.plusDays(7));
    }

    @Test
    void activeStartedPeriodIsPreservedWithoutReloadingThePlan() {
        MembershipQuotaPlanService plans = mock(MembershipQuotaPlanService.class);
        UserMembershipQuota quota = quota(MembershipTier.GO);
        quota.setQuotaBalanceMinor(12_345L);
        quota.setQuotaPeriodStartedAt(FIRST_USAGE_AT.minusDays(1));
        quota.setQuotaPeriodEndsAt(FIRST_USAGE_AT.plusDays(6));

        new MembershipQuotaPeriodActivationServiceImpl(plans)
                .activateIfDue(quota, FIRST_USAGE_AT);

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(12_345L);
        assertThat(quota.getQuotaPeriodStartedAt())
                .isEqualTo(FIRST_USAGE_AT.minusDays(1));
        verify(plans, never()).getRequired(MembershipTier.GO);
    }

    @Test
    void expiredPeriodUsesTheSameFirstUsageActivationRule() {
        MembershipQuotaPlanService plans = mock(MembershipQuotaPlanService.class);
        when(plans.getRequired(MembershipTier.PRO))
                .thenReturn(new MembershipQuotaPlan(1_000_000L, Duration.ofDays(7)));
        UserMembershipQuota quota = quota(MembershipTier.PRO);
        quota.setQuotaBalanceMinor(1L);
        quota.setQuotaPeriodStartedAt(FIRST_USAGE_AT.minusDays(8));
        quota.setQuotaPeriodEndsAt(FIRST_USAGE_AT.minusSeconds(1));

        new MembershipQuotaPeriodActivationServiceImpl(plans)
                .activateIfDue(quota, FIRST_USAGE_AT);

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(1_000_000L);
        assertThat(quota.getQuotaPeriodStartedAt()).isEqualTo(FIRST_USAGE_AT);
        assertThat(quota.getQuotaPeriodEndsAt())
                .isEqualTo(FIRST_USAGE_AT.plusDays(7));
    }

    @Test
    void unknownTierFailsWithoutMutatingTheQuota() {
        MembershipQuotaPlanService plans = mock(MembershipQuotaPlanService.class);
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setMembershipTier(99);
        quota.setQuotaBalanceMinor(321L);
        quota.setQuotaPeriodEndsAt(FIRST_USAGE_AT.minusSeconds(1));

        assertThatThrownBy(() -> new MembershipQuotaPeriodActivationServiceImpl(plans)
                        .activateIfDue(quota, FIRST_USAGE_AT))
                .isInstanceOf(MembershipQuotaPeriodActivationException.class);

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(321L);
        assertThat(quota.getQuotaPeriodStartedAt()).isNull();
        verify(plans, never()).getRequired(MembershipTier.FREE);
    }

    private static UserMembershipQuota quota(MembershipTier tier) {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(17L);
        quota.setMembershipTier(tier.ordinal());
        return quota;
    }
}
