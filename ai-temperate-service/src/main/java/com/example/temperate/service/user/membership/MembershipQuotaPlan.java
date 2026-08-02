package com.example.temperate.service.user.membership;

import java.time.Duration;

/**
 * 表示一个会员套餐可重复使用的额度总量与滚动周期规则。
 *
 * <p>该值对象只描述配置规则，不保存某个用户的实时余额或周期起止时间。</p>
 */
public record MembershipQuotaPlan(long totalMinor, Duration period) {

    public MembershipQuotaPlan {
        if (totalMinor <= 0L) {
            throw new IllegalArgumentException("Membership quota total must be positive.");
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("Membership quota period must be positive.");
        }
    }
}
