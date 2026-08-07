package com.example.temperate.service.user.aiconversation.reconciliation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelUsageRefundCandidate;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证遗留 RESERVED 只在最大流时长和安全缓冲之后批量转为待对账。
 */
final class AiConversationReconciliationServiceImplTest {

    @Test
    void historicalRefundDoesNotReadOrWriteWhenOptInIsDisabled() {
        AiModelUsageMapper usageMapper = mock(AiModelUsageMapper.class);
        AiModelUsageDetailMapper detailMapper =
                mock(AiModelUsageDetailMapper.class);
        UserMembershipQuotaMapper quotaMapper =
                mock(UserMembershipQuotaMapper.class);
        UserProfileCacheInvalidationExecutor cacheInvalidation =
                mock(UserProfileCacheInvalidationExecutor.class);
        AiConversationReconciliationServiceImpl service =
                new AiConversationReconciliationServiceImpl(
                        usageMapper,
                        detailMapper,
                        quotaMapper,
                        properties(false),
                        new AiInferenceProperties(
                                false,
                                "https://cli-proxy.example.test/v1",
                                "",
                                Duration.ofMinutes(15)),
                        Clock.fixed(
                                Instant.parse("2026-07-30T12:00:00Z"),
                                ZoneOffset.UTC),
                        cacheInvalidation,
                        new AiConversationMetrics(new SimpleMeterRegistry()));

        assertThat(service.refundHistoricalSystemFailures()).isZero();
        verifyNoInteractions(
                usageMapper, detailMapper, quotaMapper, cacheInvalidation);
    }

    @Test
    void marksAtMostConfiguredBatchWithoutRefundingQuota() {
        AiModelUsageMapper mapper = mock(AiModelUsageMapper.class);
        AiModelUsageDetailMapper detailMapper =
                mock(AiModelUsageDetailMapper.class);
        UserMembershipQuotaMapper quotaMapper =
                mock(UserMembershipQuotaMapper.class);
        UserProfileCacheInvalidationExecutor cacheInvalidation =
                mock(UserProfileCacheInvalidationExecutor.class);
        when(mapper.markExpiredReservationsForReconciliation(
                any(Integer.class),
                any(Integer.class),
                any(OffsetDateTime.class),
                any(Integer.class),
                any(String.class),
                any(OffsetDateTime.class))).thenReturn(7);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
        AiConversationProperties conversation = properties(false);
        AiInferenceProperties inference = new AiInferenceProperties(
                false,
                "https://cli-proxy.example.test/v1",
                "",
                Duration.ofMinutes(15));
        AiConversationReconciliationServiceImpl service =
                new AiConversationReconciliationServiceImpl(
                        mapper,
                        detailMapper,
                        quotaMapper,
                        conversation,
                        inference,
                        clock,
                        cacheInvalidation,
                        new AiConversationMetrics(
                                new SimpleMeterRegistry()));

        assertThat(service.reconcileExpiredReservations()).isEqualTo(7);
        verify(mapper).markExpiredReservationsForReconciliation(
                eq(AiModelBillingStatus.RESERVED.code()),
                eq(AiModelBillingStatus.RECONCILE_REQUIRED.code()),
                eq(OffsetDateTime.parse("2026-07-30T11:43:00Z")),
                eq(500),
                eq("AI_RESERVED_EXPIRED"),
                eq(OffsetDateTime.parse("2026-07-30T12:00:00Z")));
        verifyNoInteractions(detailMapper, quotaMapper, cacheInvalidation);
    }

    @Test
    void refundsAllowlistedHistoricalFailuresInOneBoundedTransactionBatch() {
        AiModelUsageMapper usageMapper = mock(AiModelUsageMapper.class);
        AiModelUsageDetailMapper detailMapper =
                mock(AiModelUsageDetailMapper.class);
        UserMembershipQuotaMapper quotaMapper =
                mock(UserMembershipQuotaMapper.class);
        UserProfileCacheInvalidationExecutor cacheInvalidation =
                mock(UserProfileCacheInvalidationExecutor.class);
        List<AiModelUsageRefundCandidate> candidates = List.of(
                new AiModelUsageRefundCandidate(new byte[] {1}, 9L, 241L),
                new AiModelUsageRefundCandidate(new byte[] {2}, 9L, 241L));
        when(usageMapper.findSystemFailureRefundCandidatesForUpdate(
                eq(AiModelBillingStatus.RECONCILE_REQUIRED.code()),
                any(),
                eq(500))).thenReturn(candidates);
        when(quotaMapper.addHistoricalAiRefunds(candidates)).thenReturn(1);
        when(usageMapper.markHistoricalSystemFailuresRefunded(
                candidates,
                AiModelBillingStatus.RECONCILE_REQUIRED.code(),
                AiModelBillingStatus.REFUNDED.code(),
                OffsetDateTime.parse("2026-07-30T12:00:00Z")))
                .thenReturn(2);
        when(detailMapper.finalizeHistoricalRefunds(candidates)).thenReturn(2);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
        AiInferenceProperties inference = new AiInferenceProperties(
                false,
                "https://cli-proxy.example.test/v1",
                "",
                Duration.ofMinutes(15));
        AiConversationReconciliationServiceImpl service =
                new AiConversationReconciliationServiceImpl(
                        usageMapper,
                        detailMapper,
                        quotaMapper,
                        properties(true),
                        inference,
                        clock,
                        cacheInvalidation,
                        new AiConversationMetrics(new SimpleMeterRegistry()));

        assertThat(service.refundHistoricalSystemFailures()).isEqualTo(2);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<String>> failureCodes = (ArgumentCaptor)
                ArgumentCaptor.forClass(List.class);
        verify(usageMapper).findSystemFailureRefundCandidatesForUpdate(
                eq(AiModelBillingStatus.RECONCILE_REQUIRED.code()),
                failureCodes.capture(),
                eq(500));
        assertThat(failureCodes.getValue())
                .doesNotContain("AI_IMAGE_COST_EVIDENCE_MISSING");
        verify(cacheInvalidation).evictAfterCommit(List.of(9L));
    }

    private static AiConversationProperties properties(
            boolean historicalAutoRefundEnabled) {
        return new AiConversationProperties(
                Duration.ofHours(72),
                Duration.ofSeconds(45),
                Duration.ofMinutes(5),
                Duration.ofMillis(250),
                4096,
                500,
                5000,
                1000,
                4,
                128,
                2048,
                80,
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                500,
                historicalAutoRefundEnabled,
                "test prompt");
    }
}
