package com.example.temperate.service.user.profile;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.OffsetDateTime;

/**
 * 表示当前用户资料接口可以直接返回的账号、会员和额度展示结果。
 *
 * <p>余额、总额、已用量和使用率均使用十进制字符串，防止 JavaScript 整数精度和浮点舍入问题；
 * 重置时间可能是数据库有效周期终点，也可能是过期后按照当前 UTC 时间计算出的七天预计时间。</p>
 */
public record CurrentUserProfileResult(
        String displayName,
        String email,
        String phone,
        String avatarUrl,
        MembershipTier membershipTier,
        String quotaBalanceMinor,
        String quotaBalance,
        String quotaTotalMinor,
        String quotaTotal,
        String quotaUsedMinor,
        String quotaUsed,
        String quotaUsagePercent,
        OffsetDateTime quotaPeriodStartedAt,
        OffsetDateTime quotaResetAt) {
}
