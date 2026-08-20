package com.example.temperate.service.user.membership.purchase;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来向会员转换策略提供当前等级、目标等级和当前付费订阅到期时间。
 *
 * <p>FREE 的到期时间可以为空；付费等级到期时间为空时由策略按已经失效处理。</p>
 */
public record MembershipTransitionCommand(
        MembershipTier currentTier,
        MembershipTier targetTier,
        OffsetDateTime membershipExpiresAt) {

    public MembershipTransitionCommand {
        Objects.requireNonNull(currentTier, "Current membership tier is required.");
        Objects.requireNonNull(targetTier, "Target membership tier is required.");
    }
}
