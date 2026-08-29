package com.example.temperate.service.user.membership.impl;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.membership.MembershipQuotaPeriodActivationException;
import com.example.temperate.service.user.membership.MembershipQuotaPeriodActivationService;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把“尚未激活”和“既有周期已到期”统一转换为从第一次有效预扣开始的新额度周期。
 *
 * <p>支付发放会把 startedAt 留空并把 endsAt 写为 paidAt；因此 startedAt 为空始终优先表示尚未使用，即使外部支付时间存在轻微时钟偏移。</p>
 */
@Service
public final class MembershipQuotaPeriodActivationServiceImpl
        implements MembershipQuotaPeriodActivationService {

    private final MembershipQuotaPlanService quotaPlanService;

    public MembershipQuotaPeriodActivationServiceImpl(
            MembershipQuotaPlanService quotaPlanService) {
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
    }

    @Override
    public void activateIfDue(
            UserMembershipQuota quota,
            OffsetDateTime firstUsageAt) {
        UserMembershipQuota current = Objects.requireNonNull(
                quota, "quota must not be null");
        OffsetDateTime usageAt = Objects.requireNonNull(
                        firstUsageAt, "firstUsageAt must not be null")
                .withOffsetSameInstant(ZoneOffset.UTC);
        if (current.getQuotaPeriodStartedAt() != null
                && current.getQuotaPeriodEndsAt() != null
                && current.getQuotaPeriodEndsAt().isAfter(usageAt)) {
            return;
        }
        MembershipTier tier = resolveTier(current.getMembershipTier());
        MembershipQuotaPlan plan;
        try {
            plan = quotaPlanService.getRequired(tier);
        } catch (RuntimeException exception) {
            throw new MembershipQuotaPeriodActivationException(
                    "Membership quota period rule is unavailable.", exception);
        }

        // 调用方已通过 FOR UPDATE 锁定额度行；完整额度重置、本次预扣和 usage 写入将在同一事务中提交或回滚。
        current.setQuotaBalanceMinor(plan.totalMinor());
        current.setQuotaPeriodStartedAt(usageAt);
        current.setQuotaPeriodEndsAt(usageAt.plus(plan.period()));
    }

    private static MembershipTier resolveTier(Integer code) {
        if (code == null || code < 0 || code >= MembershipTier.values().length) {
            throw new MembershipQuotaPeriodActivationException(
                    "Membership quota tier is invalid.");
        }
        return MembershipTier.values()[code];
    }
}
