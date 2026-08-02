package com.example.temperate.service.user.membership.impl;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.config.MembershipQuotaProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 从已校验配置构建不可变的会员额度计划表，供所有额度业务共享同一事实来源。
 */
@Service
public final class MembershipQuotaPlanServiceImpl
        implements MembershipQuotaPlanService {

    private final Map<MembershipTier, MembershipQuotaPlan> plans;

    public MembershipQuotaPlanServiceImpl(MembershipQuotaProperties properties) {
        Objects.requireNonNull(properties);
        if (!properties.isPeriodValid() || !properties.areLimitsValid()) {
            throw new IllegalArgumentException(
                    "Membership quota configuration is incomplete or invalid.");
        }
        EnumMap<MembershipTier, MembershipQuotaPlan> configured =
                new EnumMap<>(MembershipTier.class);
        for (MembershipTier tier : MembershipTier.values()) {
            configured.put(
                    tier,
                    new MembershipQuotaPlan(
                            properties.limits().get(tier),
                            properties.period()));
        }
        this.plans = Map.copyOf(configured);
    }

    @Override
    public MembershipQuotaPlan getRequired(MembershipTier membershipTier) {
        MembershipQuotaPlan plan = plans.get(Objects.requireNonNull(membershipTier));
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Unsupported membership tier: " + membershipTier);
        }
        return plan;
    }
}
