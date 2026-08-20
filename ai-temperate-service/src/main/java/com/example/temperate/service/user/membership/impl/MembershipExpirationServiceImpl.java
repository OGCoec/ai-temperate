package com.example.temperate.service.user.membership.impl;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来用一条 PostgreSQL 条件更新完成付费会员惰性降级，并在提交后清除个人中心缓存。
 *
 * <p>FREE 权益总量与七天周期继续来自现有套餐配置；没有匹配到期记录时不产生数据库行写入，也不触发缓存删除。</p>
 */
@Service
public final class MembershipExpirationServiceImpl
        implements MembershipExpirationService {

    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipQuotaPlanService quotaPlanService;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final Clock clock;

    public MembershipExpirationServiceImpl(
            UserMembershipQuotaMapper quotaMapper,
            MembershipQuotaPlanService quotaPlanService,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            Clock clock) {
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.cacheInvalidationExecutor = Objects.requireNonNull(cacheInvalidationExecutor);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 使用数据库条件更新作为并发裁决，并把本次降级与提交后缓存失效绑定在同一个事务边界中。
     */
    @Override
    @Transactional
    public boolean expireIfDue(long loginIdentityId) {
        if (loginIdentityId <= 0L) {
            throw new IllegalArgumentException("Login identity ID must be positive.");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MembershipQuotaPlan freePlan =
                quotaPlanService.getRequired(MembershipTier.FREE);
        OffsetDateTime freeQuotaEndsAt = now.plus(freePlan.period());

        // 条件 UPDATE 在取得行锁后会重新判断等级和到期时间，因此并发认证只能有一个请求实际降级。
        int affected = quotaMapper.expirePaidMembershipIfDue(
                loginIdentityId,
                now,
                freePlan.totalMinor(),
                freeQuotaEndsAt);
        if (affected == 0) {
            return false;
        }
        if (affected != 1) {
            throw new IllegalStateException(
                    "Membership expiration affected an unexpected row count: " + affected);
        }
        // 缓存删除必须注册在当前本地事务提交之后，避免回滚时把旧数据库状态错误投影为缓存未命中。
        cacheInvalidationExecutor.evictAfterCommit(loginIdentityId);
        return true;
    }
}
