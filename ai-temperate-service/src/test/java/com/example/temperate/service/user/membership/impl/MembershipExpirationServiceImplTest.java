package com.example.temperate.service.user.membership.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该测试是来锁定付费会员惰性过期时的 FREE 权益重置、影响行数和提交后缓存失效边界。
 */
final class MembershipExpirationServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-20T12:00:00Z");
    private static final OffsetDateTime FREE_ENDS_AT =
            OffsetDateTime.parse("2026-08-27T12:00:00Z");

    private UserMembershipQuotaMapper quotaMapper;
    private UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private MembershipExpirationServiceImpl service;

    @BeforeEach
    void setUp() {
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        cacheInvalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        MembershipQuotaPlanService quotaPlanService = tier -> {
            assertThat(tier).isEqualTo(MembershipTier.FREE);
            return new MembershipQuotaPlan(5_000L, Duration.ofDays(7));
        };
        service = new MembershipExpirationServiceImpl(
                quotaMapper,
                quotaPlanService,
                cacheInvalidationExecutor,
                CLOCK);
    }

    @Test
    void returnsFalseWithoutInvalidatingCacheWhenNoPaidMembershipExpired() {
        when(quotaMapper.expirePaidMembershipIfDue(
                10001L, NOW, 5_000L, FREE_ENDS_AT)).thenReturn(0);

        assertThat(service.expireIfDue(10001L)).isFalse();

        verify(cacheInvalidationExecutor, never()).evictAfterCommit(10001L);
    }

    @Test
    void resetsExpiredPaidMembershipAndInvalidatesProfileCacheAfterCommit() {
        when(quotaMapper.expirePaidMembershipIfDue(
                10001L, NOW, 5_000L, FREE_ENDS_AT)).thenReturn(1);

        assertThat(service.expireIfDue(10001L)).isTrue();

        verify(quotaMapper).expirePaidMembershipIfDue(
                10001L, NOW, 5_000L, FREE_ENDS_AT);
        verify(cacheInvalidationExecutor).evictAfterCommit(10001L);
    }

    @Test
    void rejectsImpossibleAffectedRowCountWithoutInvalidatingCache() {
        when(quotaMapper.expirePaidMembershipIfDue(
                10001L, NOW, 5_000L, FREE_ENDS_AT)).thenReturn(2);

        assertThatThrownBy(() -> service.expireIfDue(10001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("affected an unexpected row count");

        verify(cacheInvalidationExecutor, never()).evictAfterCommit(10001L);
    }

    @Test
    void publicExpirationBoundaryIsTransactional() throws NoSuchMethodException {
        assertThat(MembershipExpirationServiceImpl.class
                .getMethod("expireIfDue", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
