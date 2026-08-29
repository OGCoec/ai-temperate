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
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentRestrictedFixtureServiceImpl;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 该测试是来锁定 EDU/TEAM 固定夹具只能修改四个批准账号，并能跨重启从受保护快照幂等恢复全部额度字段。
 */
final class MembershipPaymentRestrictedFixtureServiceImplTest {

    private static final List<Long> IDS = List.of(
            84758509811535872L,
            84758866549673984L,
            84759380653903872L,
            84760794662834176L);
    private static final Instant NOW = Instant.parse("2026-08-22T17:00:00Z");

    @TempDir
    Path tempDir;

    private UserMembershipQuotaMapper mapper;
    private MembershipQuotaPlanService planService;
    private UserProfileCacheInvalidationExecutor invalidationExecutor;
    private ObjectMapper objectMapper;
    private Path snapshotPath;

    @BeforeEach
    void setUp() {
        mapper = mock(UserMembershipQuotaMapper.class);
        planService = mock(MembershipQuotaPlanService.class);
        invalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        snapshotPath = tempDir.resolve("restricted-fixtures.json");
        when(planService.getRequired(MembershipTier.EDU))
                .thenReturn(new MembershipQuotaPlan(20_000L, Duration.ofDays(7)));
        when(planService.getRequired(MembershipTier.TEAM))
                .thenReturn(new MembershipQuotaPlan(30_000L, Duration.ofDays(7)));
    }

    @Test
    void preparesOnlyTheFixedUsersAndPersistsTheirCompleteOriginalSnapshot() throws Exception {
        List<UserMembershipQuota> originals = originals();
        when(mapper.findByLoginIdentityIdsForUpdate(IDS)).thenReturn(originals);
        when(mapper.batchGrantPaidMemberships(anyString())).thenReturn(4);
        MembershipPaymentRestrictedFixtureService service = service(true);

        MembershipPaymentRestrictedFixtureState result = service.prepare();

        assertThat(result.prepared()).isTrue();
        assertThat(result.snapshotPresent()).isTrue();
        assertThat(result.users()).extracting(MembershipPaymentRestrictedFixtureUser::tier)
                .containsExactly("EDU", "EDU", "TEAM", "TEAM");
        assertThat(snapshotPath).exists();
        JsonNode snapshot = objectMapper.readTree(Files.readAllBytes(snapshotPath));
        assertThat(snapshot.path("users")).hasSize(4);
        assertThat(snapshot.path("users").get(0).path("quotaBalanceMinor").asLong())
                .isEqualTo(1_000L);
        ArgumentCaptor<String> grants = ArgumentCaptor.forClass(String.class);
        verify(mapper).batchGrantPaidMemberships(grants.capture());
        JsonNode target = objectMapper.readTree(grants.getValue());
        assertThat(target.get(0).path("membershipTier").asInt())
                .isEqualTo(MembershipTier.EDU.ordinal());
        assertThat(target.get(2).path("membershipTier").asInt())
                .isEqualTo(MembershipTier.TEAM.ordinal());
        assertThat(target.get(0).path("quotaBalanceMinor").asLong()).isEqualTo(20_000L);
        assertThat(target.get(2).path("quotaBalanceMinor").asLong()).isEqualTo(30_000L);
        verify(invalidationExecutor).evictAfterCommit(IDS);
    }

    @Test
    void restoresTheOriginalRowsAndDeletesTheSnapshotOnlyAfterCommit() {
        when(mapper.findByLoginIdentityIdsForUpdate(IDS)).thenReturn(originals());
        when(mapper.batchGrantPaidMemberships(anyString())).thenReturn(4);
        MembershipPaymentRestrictedFixtureService service = service(true);
        service.prepare();

        TransactionSynchronizationManager.initSynchronization();
        try {
            MembershipPaymentRestrictedFixtureState result = service.restore();

            assertThat(result.prepared()).isFalse();
            assertThat(snapshotPath).exists();
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(snapshotPath).doesNotExist();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void refusesPartialFixtureRowsWithoutWritingARecoverabilitySnapshot() {
        when(mapper.findByLoginIdentityIdsForUpdate(IDS))
                .thenReturn(originals().subList(0, 3));

        assertThatThrownBy(() -> service(true).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("four");
        assertThat(snapshotPath).doesNotExist();
    }

    @Test
    void disabledLoadtestCannotMutateFixtures() {
        assertThatThrownBy(() -> service(false).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    private MembershipPaymentRestrictedFixtureService service(boolean enabled) {
        return new MembershipPaymentRestrictedFixtureServiceImpl(
                new MembershipPaymentLoadtestProperties(enabled, enabled ? IDS : List.of()),
                mapper,
                planService,
                invalidationExecutor,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                snapshotPath.toString());
    }

    private static List<UserMembershipQuota> originals() {
        return java.util.stream.IntStream.range(0, IDS.size())
                .mapToObj(index -> {
                    UserMembershipQuota quota = new UserMembershipQuota();
                    quota.setId(100L + index);
                    quota.setLoginIdentityId(IDS.get(index));
                    quota.setMembershipTier(MembershipTier.FREE.ordinal());
                    quota.setQuotaBalanceMinor(1_000L + index);
                    quota.setQuotaPeriodStartedAt(OffsetDateTime.ofInstant(
                            NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC));
                    quota.setQuotaPeriodEndsAt(OffsetDateTime.ofInstant(
                            NOW.plus(Duration.ofDays(6)), ZoneOffset.UTC));
                    quota.setMembershipExpiresAt(null);
                    return quota;
                })
                .toList();
    }
}
