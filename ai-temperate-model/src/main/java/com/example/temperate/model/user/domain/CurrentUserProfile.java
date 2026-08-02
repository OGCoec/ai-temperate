package com.example.temperate.model.user.domain;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.OffsetDateTime;

/**
 * 表示已认证用户个人中心从 PostgreSQL 一次联查得到的资料与额度原始快照。
 *
 * <p>该边界排除内部用户 ID、密码和令牌，也不计算随当前时间变化的额度展示；Service 可以把相同原始值
 * 写入 Redis String，并在每次响应时独立投影预计重置结果。</p>
 */
public record CurrentUserProfile(
        String displayName,
        String email,
        String phone,
        String avatarUrl,
        MembershipTier membershipTier,
        long quotaBalanceMinor,
        OffsetDateTime quotaPeriodStartedAt,
        OffsetDateTime quotaPeriodEndsAt) {
}
