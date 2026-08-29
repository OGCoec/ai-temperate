package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentBaselineFixtureServiceImpl;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 该测试是来锁定本地正式浸泡开始前只能把批准的十六个账号事务化恢复为统一 FREE 未激活基线。
 */
final class MembershipPaymentBaselineFixtureServiceImplTest {

    private static final List<Long> IDS = List.of(
            72659006262480896L,
            73014701344296960L,
            74891801495998464L,
            76721355290185728L,
            84736921162616832L,
            84739559597936640L,
            84742296792338432L,
            84745417706835968L,
            84746552547086336L,
            84753114204344320L,
            84754367089086464L,
            84755204414771200L,
            84758509811535872L,
            84758866549673984L,
            84759380653903872L,
            84760794662834176L);
    private static final Instant NOW = Instant.parse("2026-08-23T06:00:00Z");

    private UserMembershipQuotaMapper mapper;
    private MembershipQuotaPlanService planService;
    private UserProfileCacheInvalidationExecutor invalidationExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = mock(UserMembershipQuotaMapper.class);
        planService = mock(MembershipQuotaPlanService.class);
        invalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(planService.getRequired(MembershipTier.FREE))
                .thenReturn(new MembershipQuotaPlan(5_000L, Duration.ofDays(7)));
    }

    @Test
    void preparesExactlySixteenApprovedUsersAsFreeUnactivatedQuotaRows() throws Exception {
        when(mapper.findByLoginIdentityIdsForUpdate(IDS)).thenReturn(rows());
        when(mapper.batchGrantPaidMemberships(anyString())).thenReturn(16);
        MembershipPaymentBaselineFixtureService service = service(true, IDS);

        MembershipPaymentBaselineFixtureState result = service.prepare();

        assertThat(result.prepared()).isTrue();
        assertThat(result.users()).hasSize(16);
        assertThat(result.users())
                .extracting(MembershipPaymentBaselineFixtureUser::tier)
                .containsOnly("FREE");
        ArgumentCaptor<String> grants = ArgumentCaptor.forClass(String.class);
        verify(mapper).batchGrantPaidMemberships(grants.capture());
        JsonNode payload = objectMapper.readTree(grants.getValue());
        assertThat(payload).hasSize(16);
        for (JsonNode row : payload) {
            assertThat(row.path("membershipTier").asInt())
                    .isEqualTo(MembershipTier.FREE.ordinal());
            assertThat(row.path("quotaBalanceMinor").asLong()).isEqualTo(5_000L);
            assertThat(row.path("quotaPeriodStartedAt").isNull()).isTrue();
            assertThat(row.path("quotaPeriodEndsAt").asDouble())
                    .isEqualTo((double) NOW.getEpochSecond());
            assertThat(row.path("membershipExpiresAt").isNull()).isTrue();
        }
        verify(invalidationExecutor).evictAfterCommit(IDS);
    }

    @Test
    void refusesAnAllowlistThatIsNotExactlyTheApprovedSixteenUsers() {
        assertThatThrownBy(() -> service(true, IDS.subList(0, 15)).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void refusesPartialQuotaRowsWithoutIssuingAnUpdate() {
        when(mapper.findByLoginIdentityIdsForUpdate(IDS))
                .thenReturn(rows().subList(0, 15));

        assertThatThrownBy(() -> service(true, IDS).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sixteen");
    }

    private MembershipPaymentBaselineFixtureService service(
            boolean enabled,
            List<Long> allowlist) {
        return new MembershipPaymentBaselineFixtureServiceImpl(
                new MembershipPaymentLoadtestProperties(enabled, allowlist),
                mapper,
                planService,
                invalidationExecutor,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<UserMembershipQuota> rows() {
        return java.util.stream.IntStream.range(0, IDS.size())
                .mapToObj(index -> {
                    UserMembershipQuota quota = new UserMembershipQuota();
                    quota.setId(100L + index);
                    quota.setLoginIdentityId(IDS.get(index));
                    quota.setMembershipTier(index % 2 == 0
                            ? MembershipTier.GO.ordinal()
                            : MembershipTier.PLUS.ordinal());
                    quota.setQuotaBalanceMinor(1_000L + index);
                    return quota;
                })
                .toList();
    }
}
