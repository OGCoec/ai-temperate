package com.example.temperate.service.user.profile.cache;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.OffsetDateTime;

/**
 * 表示单个普通用户在 Redis String 中保存的版本化明文 JSON 资料快照。
 *
 * <p>该快照只承载个人中心展示所需的数据库原始值，不保存内部用户 ID、认证凭据或根据当前时间计算出的
 * 预计重置结果；动态额度展示由 Service 使用统一 {@code Clock} 每次重新计算。</p>
 */
public record UserProfileCacheValue(
        int schemaVersion,
        String displayName,
        String email,
        String phone,
        String avatarUrl,
        MembershipTier membershipTier,
        long quotaBalanceMinor,
        OffsetDateTime quotaPeriodStartedAt,
        OffsetDateTime quotaPeriodEndsAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public UserProfileCacheValue {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported user profile cache schema version.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Cached user display name is required.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Cached user email is required.");
        }
        if (membershipTier == null) {
            throw new IllegalArgumentException("Cached membership tier is required.");
        }
        if (quotaBalanceMinor < 0) {
            throw new IllegalArgumentException("Cached quota balance cannot be negative.");
        }
    }
}
