package com.example.temperate.service.auth.identity.bloom.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomObserver;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import com.example.temperate.service.auth.identity.bloom.store.IdentityPresenceBloomStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证身份过滤器在 Redis 异常时 Fail Open，并且永不把明文联系方式交给 Redis 存储层。
 */
class IdentityPresenceFilterImplTest {

    private IdentityPresenceBloomStore store;
    private UserLoginIdentityMapper identityMapper;
    private ScheduledExecutorService executor;
    private IdentityPresenceBloomObserver observer;
    private IdentityPresenceFilterImpl filter;

    @BeforeEach
    void setUp() {
        store = mock(IdentityPresenceBloomStore.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        executor = mock(ScheduledExecutorService.class);
        observer = mock(IdentityPresenceBloomObserver.class);
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        filter = new IdentityPresenceFilterImpl(
                identityMapper,
                store,
                hmac,
                new IdentityPresenceBloomSettings(
                        true, 1_000_000, 7, 1, 1_000_000, 500, 256, 100_000),
                executor,
                observer,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void returnsUnavailableWhenRedisLookupFails() {
        when(store.check(any(), any())).thenThrow(new IllegalStateException("redis down"));

        IdentityPresenceDecision result = filter.checkEmail("user@example.com");

        assertThat(result).isEqualTo(IdentityPresenceDecision.UNAVAILABLE);
        verify(observer).query(IdentityPresenceKind.EMAIL, IdentityPresenceDecision.UNAVAILABLE);
        verify(executor).schedule(any(Runnable.class), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void protectsEmailAndE164PhoneBeforeAtomicMutation() {
        when(store.add(any())).thenReturn(IdentityPresenceMutationResult.APPLIED);
        ArgumentCaptor<ProtectedIdentityPresenceRecord> record =
                ArgumentCaptor.forClass(ProtectedIdentityPresenceRecord.class);

        IdentityPresenceMutationResult result = filter.recordRegistration(
                10001L, "user@example.com", "+8613812345678");

        assertThat(result).isEqualTo(IdentityPresenceMutationResult.APPLIED);
        verify(store).add(record.capture());
        assertThat(record.getValue().userId()).isEqualTo(10001L);
        assertThat(record.getValue().protectedEmail().value())
                .doesNotContain("user", "example");
        assertThat(record.getValue().protectedPhone().value())
                .doesNotContain("13812345678", "+86");
    }

    @Test
    void overflowMarksFilterDegradedAndSchedulesRebuild() {
        when(store.add(any())).thenReturn(IdentityPresenceMutationResult.OVERFLOW);

        assertThat(filter.recordRegistration(
                        10001L, "user@example.com", "+8613812345678"))
                .isEqualTo(IdentityPresenceMutationResult.OVERFLOW);

        verify(store).markDegraded("COUNTER_OVERFLOW");
        verify(observer).degraded("COUNTER_OVERFLOW");
        verify(executor).schedule(any(Runnable.class), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void committedRegistrationReturnsUnavailableInsteadOfThrowingWhenRedisFails() {
        when(store.add(any())).thenThrow(new IllegalStateException("redis down"));

        assertThat(filter.recordRegistration(
                        10001L, "user@example.com", "+8613812345678"))
                .isEqualTo(IdentityPresenceMutationResult.UNAVAILABLE);

        verify(store).markDegraded("UPDATE_FAILED");
        verify(observer).mutation(IdentityPresenceMutationResult.UNAVAILABLE);
        verify(executor).schedule(any(Runnable.class), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void capacityBoundaryStaysDegradedWithoutSameCapacityRebuildLoop() {
        when(store.add(any())).thenReturn(
                IdentityPresenceMutationResult.CAPACITY_EXCEEDED);

        assertThat(filter.recordRegistration(
                        10001L, "user@example.com", "+8613812345678"))
                .isEqualTo(IdentityPresenceMutationResult.CAPACITY_EXCEEDED);

        verify(observer).degraded("CAPACITY_EXCEEDED");
        verify(store, never()).markDegraded(any());
        verifyNoInteractions(executor);
    }

    @Test
    void recordsFalsePositiveOnlyAfterPossibleHitIsAbsentInDatabase() {
        filter.recordDatabaseVerification(
                IdentityPresenceKind.EMAIL,
                IdentityPresenceDecision.POSSIBLY_PRESENT,
                false);
        filter.recordDatabaseVerification(
                IdentityPresenceKind.PHONE,
                IdentityPresenceDecision.DEFINITELY_ABSENT,
                false);

        verify(observer).falsePositive(IdentityPresenceKind.EMAIL);
        verify(observer, never()).falsePositive(IdentityPresenceKind.PHONE);
    }

    @Test
    void backgroundBuildUsesKeysetPagesBeforeReadyAndActiveTransitions() {
        UserLoginIdentity identity = new UserLoginIdentity();
        identity.setId(10001L);
        identity.setEmail("user@example.com");
        identity.setPhone("+8613812345678");
        when(store.tryAcquireBuildLease(any(), any())).thenReturn(true);
        when(store.beginBuild(any())).thenReturn(null);
        when(store.addAll(any())).thenReturn(IdentityPresenceMutationResult.APPLIED);
        when(store.renewBuildLease(any(), any())).thenReturn(true);
        when(identityMapper.findIdentityContactsAfterId(0L, 500))
                .thenReturn(List.of(identity));
        when(identityMapper.findIdentityContactsAfterId(10001L, 500))
                .thenReturn(List.of());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        filter.initializeInBackground();
        verify(executor).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();

        verify(store).markReady(any());
        verify(store).activate(any());
        verify(store).releaseBuildLease(any());
    }

    @Test
    void unavailableQueriesDuringRunningBuildDoNotQueueAnotherFullBuild() {
        when(store.tryAcquireBuildLease(any(), any())).thenReturn(true);
        when(store.beginBuild(any())).thenReturn(null);
        when(store.check(any(), any())).thenReturn(IdentityPresenceDecision.UNAVAILABLE);
        when(identityMapper.findIdentityContactsAfterId(0L, 500))
                .thenAnswer(invocation -> {
                    assertThat(filter.checkEmail("user@example.com"))
                            .isEqualTo(IdentityPresenceDecision.UNAVAILABLE);
                    return List.of();
                });
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        filter.initializeInBackground();
        verify(executor).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();

        verify(executor, times(1))
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }
}
