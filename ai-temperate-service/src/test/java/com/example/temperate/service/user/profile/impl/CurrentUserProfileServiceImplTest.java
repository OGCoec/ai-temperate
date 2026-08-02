package com.example.temperate.service.user.profile.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.user.profile.cache.UserProfileCacheStore;
import com.example.temperate.service.user.profile.cache.UserProfileCacheValue;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证当前用户资料服务优先读取缓存、未命中时单次回源，并按统一时钟计算额度展示。
 */
class CurrentUserProfileServiceImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);

    private UserLoginIdentityMapper identityMapper;
    private UserProfileCacheStore cacheStore;
    private CurrentUserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UserLoginIdentityMapper.class);
        cacheStore = mock(UserProfileCacheStore.class);
        service = new CurrentUserProfileServiceImpl(
                identityMapper,
                cacheStore,
                quotaPlans(),
                CLOCK);
    }

    @Test
    void returnsActiveCachedProfileWithoutDatabaseQuery() {
        UserProfileCacheValue cached = cacheValue(
                4200L,
                OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cached));

        var result = service.getRequired(10001L);

        assertThat(result.membershipTier()).isEqualTo(MembershipTier.FREE);
        assertThat(result.quotaBalanceMinor()).isEqualTo("4200");
        assertThat(result.quotaBalance()).isEqualTo("42.0");
        assertThat(result.quotaTotalMinor()).isEqualTo("5000");
        assertThat(result.quotaTotal()).isEqualTo("50.0");
        assertThat(result.quotaUsedMinor()).isEqualTo("800");
        assertThat(result.quotaUsed()).isEqualTo("8.0");
        assertThat(result.quotaUsagePercent()).isEqualTo("16.0");
        assertThat(result.quotaResetAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        verifyNoInteractions(identityMapper);
    }

    @Test
    void loadsDatabaseOnceAndSynchronouslyBackfillsCacheOnMiss() {
        CurrentUserProfile databaseProfile = databaseProfile(
                4200L,
                OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        when(cacheStore.find(10001L)).thenReturn(Optional.empty());
        when(identityMapper.findCurrentUserProfileById(10001L))
                .thenReturn(databaseProfile);

        var result = service.getRequired(10001L);

        assertThat(result.quotaBalance()).isEqualTo("42.0");
        verify(identityMapper).findCurrentUserProfileById(10001L);
        verify(cacheStore).put(
                10001L,
                new UserProfileCacheValue(
                        UserProfileCacheValue.CURRENT_SCHEMA_VERSION,
                        databaseProfile.displayName(),
                        databaseProfile.email(),
                        databaseProfile.phone(),
                        databaseProfile.avatarUrl(),
                        databaseProfile.membershipTier(),
                        databaseProfile.quotaBalanceMinor(),
                        databaseProfile.quotaPeriodStartedAt(),
                        databaseProfile.quotaPeriodEndsAt()));
    }

    @Test
    void projectsExpiredFreeQuotaToFullBalanceAndSevenDayReset() {
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cacheValue(
                125L,
                OffsetDateTime.parse("2026-07-30T12:00:00Z"))));

        var result = service.getRequired(10001L);

        assertThat(result.quotaBalanceMinor()).isEqualTo("5000");
        assertThat(result.quotaBalance()).isEqualTo("50.0");
        assertThat(result.quotaResetAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-06T12:00:00Z"));
    }

    @Test
    void projectsExpiredNonFreeTierFromItsConfiguredPlan() {
        UserProfileCacheValue cached = new UserProfileCacheValue(
                UserProfileCacheValue.CURRENT_SCHEMA_VERSION,
                "Alice",
                "alice@example.test",
                null,
                null,
                MembershipTier.PLUS,
                125L,
                OffsetDateTime.parse("2026-07-20T12:00:00Z"),
                OffsetDateTime.parse("2026-07-29T12:00:00Z"));
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cached));

        var result = service.getRequired(10001L);

        assertThat(result.quotaBalanceMinor()).isEqualTo("200000");
        assertThat(result.quotaBalance()).isEqualTo("2000.0");
        assertThat(result.quotaTotalMinor()).isEqualTo("200000");
        assertThat(result.quotaUsedMinor()).isEqualTo("0");
        assertThat(result.quotaUsagePercent()).isEqualTo("0.0");
        assertThat(result.quotaResetAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-06T12:00:00Z"));
    }

    @Test
    void treatsMissingPeriodEndAsExpiredWithoutPersistingProjection() {
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cacheValue(125L, null)));

        var result = service.getRequired(10001L);

        assertThat(result.quotaBalanceMinor()).isEqualTo("5000");
        assertThat(result.quotaResetAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-06T12:00:00Z"));
        verify(cacheStore, never()).put(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clampsUsageToZeroWhenStoredBalanceExceedsPlanTotal() {
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cacheValue(
                5_500L,
                OffsetDateTime.parse("2026-08-01T12:00:00Z"))));

        var result = service.getRequired(10001L);

        assertThat(result.quotaUsedMinor()).isEqualTo("0");
        assertThat(result.quotaUsagePercent()).isEqualTo("0.0");
    }

    @Test
    void reportsOneHundredPercentWhenActiveBalanceIsZero() {
        when(cacheStore.find(10001L)).thenReturn(Optional.of(cacheValue(
                0L,
                OffsetDateTime.parse("2026-08-01T12:00:00Z"))));

        var result = service.getRequired(10001L);

        assertThat(result.quotaUsedMinor()).isEqualTo("5000");
        assertThat(result.quotaUsed()).isEqualTo("50.0");
        assertThat(result.quotaUsagePercent()).isEqualTo("100.0");
    }

    @Test
    void rejectsADeletedOrUnavailableAuthenticatedAccount() {
        when(cacheStore.find(10001L)).thenReturn(Optional.empty());
        when(identityMapper.findCurrentUserProfileById(10001L)).thenReturn(null);

        assertThatThrownBy(() -> service.getRequired(10001L))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE);
                    assertThat(exception.clearCookies()).isTrue();
                });
        verify(cacheStore, never()).put(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    private static CurrentUserProfile databaseProfile(
            long balance,
            OffsetDateTime endsAt) {
        return new CurrentUserProfile(
                "Alice",
                "alice@example.test",
                "+14155550123",
                "https://cdn.example.test/avatar.webp",
                MembershipTier.FREE,
                balance,
                OffsetDateTime.parse("2026-07-25T12:00:00Z"),
                endsAt);
    }

    private static UserProfileCacheValue cacheValue(
            long balance,
            OffsetDateTime endsAt) {
        CurrentUserProfile profile = databaseProfile(balance, endsAt);
        return new UserProfileCacheValue(
                UserProfileCacheValue.CURRENT_SCHEMA_VERSION,
                profile.displayName(),
                profile.email(),
                profile.phone(),
                profile.avatarUrl(),
                profile.membershipTier(),
                profile.quotaBalanceMinor(),
                profile.quotaPeriodStartedAt(),
                profile.quotaPeriodEndsAt());
    }

    private static MembershipQuotaPlanService quotaPlans() {
        return tier -> new MembershipQuotaPlan(
                switch (tier) {
                    case FREE -> 5_000L;
                    case GO -> 50_000L;
                    case EDU -> 80_000L;
                    case TEAM -> 180_000L;
                    case PLUS -> 200_000L;
                    case PRO -> 1_000_000L;
                    case MAX -> 5_000_000L;
                },
                Duration.ofDays(7));
    }
}
