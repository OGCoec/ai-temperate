package com.example.temperate.service.user.profile.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.user.profile.CurrentUserProfileResult;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheStore;
import com.example.temperate.service.user.profile.cache.UserProfileCacheValue;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 通过 Cache-Aside 读取当前用户资料，并按统一 UTC 时钟投影会员额度和预计重置时间。
 *
 * <p>Redis 只保存数据库原始快照且故障时安全回源；该服务不把资料放入认证上下文，也不承担真实额度预扣、
 * 结算或周期落库。缓存命中路径不创建数据库事务，避免为了读取 Redis 占用 PostgreSQL 连接。</p>
 */
@Service
public final class CurrentUserProfileServiceImpl implements CurrentUserProfileService {

    private static final int QUOTA_SCALE = 2;

    private final UserLoginIdentityMapper identityMapper;
    private final UserProfileCacheStore cacheStore;
    private final MembershipQuotaPlanService quotaPlanService;
    private final Clock clock;

    public CurrentUserProfileServiceImpl(
            UserLoginIdentityMapper identityMapper,
            UserProfileCacheStore cacheStore,
            MembershipQuotaPlanService quotaPlanService,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.cacheStore = Objects.requireNonNull(cacheStore);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CurrentUserProfileResult getRequired(long userId) {
        UserProfileCacheValue snapshot = cacheStore.find(userId)
                .orElseGet(() -> loadAndCache(userId));
        return project(snapshot);
    }

    private UserProfileCacheValue loadAndCache(long userId) {
        CurrentUserProfile profile = identityMapper.findCurrentUserProfileById(userId);
        if (profile == null) {
            // 认证完成后账号、资料或额度行仍可能被并发删除或禁用，必须收敛为会话失效而不是返回残缺资料。
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "当前账号不可用，请重新登录。",
                    true);
        }
        UserProfileCacheValue snapshot = new UserProfileCacheValue(
                UserProfileCacheValue.CURRENT_SCHEMA_VERSION,
                profile.displayName(),
                profile.email(),
                profile.phone(),
                profile.avatarUrl(),
                profile.membershipTier(),
                profile.quotaBalanceMinor(),
                profile.quotaPeriodStartedAt(),
                profile.quotaPeriodEndsAt());
        cacheStore.put(userId, snapshot);
        return snapshot;
    }

    private CurrentUserProfileResult project(UserProfileCacheValue snapshot) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime storedEndsAt = snapshot.quotaPeriodEndsAt();
        boolean expired = storedEndsAt == null || !storedEndsAt.isAfter(now);
        MembershipQuotaPlan plan = quotaPlanService.getRequired(
                snapshot.membershipTier());
        long effectiveBalance = expired
                ? plan.totalMinor()
                : snapshot.quotaBalanceMinor();
        // 余额可能因历史配置变更高于当前套餐总额；展示层将已用量下限钳制为零，绝不伪造负进度。
        long usedMinor = effectiveBalance >= plan.totalMinor()
                ? 0L
                : plan.totalMinor() - Math.max(effectiveBalance, 0L);
        OffsetDateTime resetAt = expired ? now.plus(plan.period()) : storedEndsAt;
        return new CurrentUserProfileResult(
                snapshot.displayName(),
                snapshot.email(),
                snapshot.phone(),
                snapshot.avatarUrl(),
                snapshot.membershipTier(),
                Long.toString(effectiveBalance),
                formatQuota(effectiveBalance),
                Long.toString(plan.totalMinor()),
                formatQuota(plan.totalMinor()),
                Long.toString(usedMinor),
                formatQuota(usedMinor),
                formatUsagePercent(usedMinor, plan.totalMinor()),
                snapshot.quotaPeriodStartedAt(),
                resetAt);
    }

    private static String formatQuota(long quotaBalanceMinor) {
        BigDecimal value = BigDecimal.valueOf(quotaBalanceMinor, QUOTA_SCALE)
                .stripTrailingZeros();
        if (value.scale() < 1) {
            value = value.setScale(1);
        }
        return value.toPlainString();
    }

    private static String formatUsagePercent(long usedMinor, long totalMinor) {
        BigDecimal percent = BigDecimal.valueOf(usedMinor)
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(totalMinor), 1, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(100L));
        return percent.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
