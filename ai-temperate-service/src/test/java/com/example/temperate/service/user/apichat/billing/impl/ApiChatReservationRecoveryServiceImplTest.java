package com.example.temperate.service.user.apichat.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelApiUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelApiUsageRefundCandidate;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定超过十五分钟流时长加两分钟缓冲的 RESERVED 批量退款，以及账号聚合和任一步不完整即回滚的事务契约。
 */
final class ApiChatReservationRecoveryServiceImplTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-13T12:00:00Z");

    private AiModelApiUsageMapper usageMapper;
    private AiModelApiUsageDetailMapper detailMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private UserProfileCacheInvalidationExecutor invalidationExecutor;
    private ApiChatReservationRecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(AiModelApiUsageMapper.class);
        detailMapper = mock(AiModelApiUsageDetailMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        invalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        service = new ApiChatReservationRecoveryServiceImpl(
                usageMapper,
                detailMapper,
                quotaMapper,
                new AiInferenceProperties(
                        true,
                        "http://127.0.0.1:8317",
                        "test-upstream-key",
                        Duration.ofMinutes(15)),
                conversationProperties(),
                invalidationExecutor,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    void refundsASeventeenMinuteBatchWithThreeBulkUpdates() {
        List<AiModelApiUsageRefundCandidate> candidates = List.of(
                candidate(501L, 17L, 2L),
                candidate(502L, 17L, 3L),
                candidate(503L, 19L, 4L));
        when(usageMapper.findExpiredReservationsForUpdate(
                AiModelBillingStatus.RESERVED.code(),
                NOW.minusMinutes(17),
                500)).thenReturn(candidates);
        when(quotaMapper.addApiRefunds(candidates)).thenReturn(2);
        when(detailMapper.finalizeRefundsBatch(candidates)).thenReturn(3);
        when(usageMapper.markRefundedBatch(
                eq(candidates),
                eq(AiModelBillingStatus.RESERVED.code()),
                eq(AiModelBillingStatus.FAILED_REFUNDED.code()),
                eq("STALE_RESERVED_RECOVERED"),
                any())).thenReturn(3);

        assertThat(service.recoverExpiredReservations()).isEqualTo(3);

        verify(invalidationExecutor).evictAfterCommit(List.of(17L, 19L));
    }

    @Test
    void incompleteBulkUpdateFailsTheWholeRecoveryTransaction() {
        List<AiModelApiUsageRefundCandidate> candidates = List.of(
                candidate(501L, 17L, 2L));
        when(usageMapper.findExpiredReservationsForUpdate(
                AiModelBillingStatus.RESERVED.code(),
                NOW.minusMinutes(17),
                500)).thenReturn(candidates);
        when(quotaMapper.addApiRefunds(candidates)).thenReturn(0);

        assertThatThrownBy(service::recoverExpiredReservations)
                .isInstanceOf(IllegalStateException.class);

        verify(invalidationExecutor, never()).evictAfterCommit(any(List.class));
    }

    private static AiModelApiUsageRefundCandidate candidate(
            long usageId,
            long loginIdentityId,
            long reservedMinor) {
        AiModelApiUsageRefundCandidate candidate =
                new AiModelApiUsageRefundCandidate();
        candidate.setUsageId(usageId);
        candidate.setLoginIdentityId(loginIdentityId);
        candidate.setReservedQuotaMinor(reservedMinor);
        return candidate;
    }

    private static AiConversationProperties conversationProperties() {
        return new AiConversationProperties(
                Duration.ofHours(72),
                Duration.ofSeconds(45),
                Duration.ofSeconds(30),
                Duration.ofMillis(250),
                4_096,
                500,
                1_000,
                900,
                10,
                128,
                1_000,
                80,
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                500,
                false,
                "test system prompt");
    }
}
