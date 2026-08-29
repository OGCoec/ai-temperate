package com.example.temperate.service.user.aiconversation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.ai.AiModelUsageVideoDetailMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.entity.AiModelUsageVideoDetail;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationCommand;
import com.example.temperate.service.user.aiconversation.billing.ProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.VideoProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPeriodActivationException;
import com.example.temperate.service.user.membership.MembershipQuotaPeriodActivationService;
import com.example.temperate.service.user.membership.impl.MembershipQuotaPeriodActivationServiceImpl;
import com.example.temperate.service.user.membership.loadtest.MembershipQuotaLoadtestFaultGate;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证模型预扣按会员周期与统一 minor 换算执行，并在额度不足时停止后续持久化。
 */
class AiConversationBillingServiceImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void activatesExpiredPlusPeriodWithConfiguredTotal() {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setMembershipTier(MembershipTier.PLUS.ordinal());
        quota.setQuotaBalanceMinor(125L);
        quota.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-07-30T12:00:00Z"));

        activationService().activateIfDue(
                quota,
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(200_000L);
        assertThat(quota.getQuotaPeriodStartedAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-31T12:00:00Z"));
        assertThat(quota.getQuotaPeriodEndsAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-07T12:00:00Z"));
    }

    @Test
    void preservesActivePeriodAndRejectsUnknownTier() {
        UserMembershipQuota active = new UserMembershipQuota();
        active.setMembershipTier(MembershipTier.FREE.ordinal());
        active.setQuotaBalanceMinor(4_200L);
        active.setQuotaPeriodStartedAt(
                OffsetDateTime.parse("2026-07-25T12:00:00Z"));
        active.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        activationService().activateIfDue(
                active,
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        assertThat(active.getQuotaBalanceMinor()).isEqualTo(4_200L);

        UserMembershipQuota invalid = new UserMembershipQuota();
        invalid.setMembershipTier(99);
        invalid.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-07-30T12:00:00Z"));
        assertThatThrownBy(() -> activationService().activateIfDue(
                        invalid,
                        OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)))
                .isInstanceOf(MembershipQuotaPeriodActivationException.class);
    }

    @Test
    void freeBalanceCanReserveOneThirdOutputCeilingUsingMinorConversion() {
        BillingFixture fixture = fixture(5_000L);

        AiConversationReservation reservation =
                fixture.service().reserve(reservationCommand());

        assertThat(reservation.reservedQuotaMinor()).isEqualTo(241L);
        assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(4_759L);
        verify(fixture.activationService()).activateIfDue(
                fixture.quota(),
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        verify(fixture.quotaMapper()).updateBalanceAndPeriod(fixture.quota());
        verify(fixture.usageMapper()).insert(any());
        verify(fixture.detailMapper()).insert(any());
    }

    @Test
    void insufficientQuotaStopsUsageWritesInsideTransactionalReservation()
            throws NoSuchMethodException {
        BillingFixture fixture = fixture(240L);

        assertThatThrownBy(() -> fixture.service().reserve(reservationCommand()))
                .isInstanceOfSatisfying(AiConversationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiConversationErrorCode.AI_QUOTA_INSUFFICIENT));
        assertThat(AiConversationBillingServiceImpl.class
                        .getMethod(
                                "reserve",
                                AiConversationReservationCommand.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(240L);
        verify(fixture.quotaMapper(), never()).updateBalanceAndPeriod(any());
        verify(fixture.usageMapper(), never()).insert(any());
        verify(fixture.detailMapper(), never()).insert(any());
    }

    @Test
    void idempotentReplayReturnsBeforeQuotaActivationOrDeduction() {
        BillingFixture fixture = fixture(5_000L);
        AiModelUsageDetail duplicate = new AiModelUsageDetail();
        duplicate.setUsageId(new byte[] {2});
        duplicate.setConversationId(new byte[] {1});
        duplicate.setMeteringBasis(AiConversationMeteringBasis.TOKEN.code());
        duplicate.setEstimatedPromptTokens(100L);
        duplicate.setMaxOutputTokens(128_000L);
        duplicate.setInputRatioSnapshot(new BigDecimal("0.75"));
        duplicate.setCachedInputRatioSnapshot(new BigDecimal("0.075"));
        duplicate.setOutputRatioSnapshot(new BigDecimal("4.5"));
        duplicate.setReservedQuotaMinor(241L);
        AiModelUsage usage = new AiModelUsage();
        usage.setId(new byte[] {2});
        usage.setLoginIdentityId(7L);
        usage.setAiModelId(9L);
        usage.setMeteringBasis(AiConversationMeteringBasis.TOKEN.code());
        usage.setBillingStatus(AiModelBillingStatus.RESERVED.code());
        when(fixture.detailMapper().findByIdempotencyDigest(any()))
                .thenReturn(duplicate);
        when(fixture.usageMapper().findByIdForUpdate(any())).thenReturn(usage);

        AiConversationReservation replay =
                fixture.service().reserve(reservationCommand());

        assertThat(replay.replay()).isTrue();
        verify(fixture.activationService(), never()).activateIfDue(any(), any());
        verify(fixture.quotaMapper(), never()).findByLoginIdentityIdForUpdate(7L);
        verify(fixture.quotaMapper(), never()).updateBalanceAndPeriod(any());
    }

    @Test
    void loadtestFaultRunsAfterReservationWritesAndBeforeAfterCommitEviction() {
        MembershipQuotaLoadtestFaultGate faultGate =
                mock(MembershipQuotaLoadtestFaultGate.class);
        BillingFixture fixture = fixture(5_000L, faultGate);
        doThrow(new IllegalStateException("rollback"))
                .when(faultGate).failAfterReservationIfArmed(7L);

        assertThatThrownBy(() -> fixture.service().reserve(reservationCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");

        verify(fixture.quotaMapper()).updateBalanceAndPeriod(fixture.quota());
        verify(fixture.usageMapper()).insert(any());
        verify(fixture.detailMapper()).insert(any());
        verify(faultGate).failAfterReservationIfArmed(7L);
        verify(fixture.cacheInvalidationExecutor(), never()).evictAfterCommit(7L);
    }

    @Test
    void providerCostReservationUsesOutputCountWithoutTokenRatios() {
        BillingFixture fixture = fixture(50_000L);
        AiConversationReservationCommand command = new AiConversationReservationCommand(
                7L,
                null,
                reservationCommand().model(),
                new byte[32],
                new ProviderCostReservationMetering((short) 3));

        AiConversationReservation reservation = fixture.service().reserve(command);

        assertThat(reservation.reservedQuotaMinor()).isEqualTo(300L);
        assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(49_700L);
        verify(fixture.activationService()).activateIfDue(
                fixture.quota(),
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        assertThat(reservation.metering().basis())
                .isEqualTo(AiConversationMeteringBasis.PROVIDER_COST_TICKS);
        ArgumentCaptor<com.example.temperate.model.ai.entity.AiModelUsageDetail>
                detail = ArgumentCaptor.forClass(
                        com.example.temperate.model.ai.entity.AiModelUsageDetail.class);
        verify(fixture.detailMapper()).insert(detail.capture());
        assertThat(detail.getValue().getRequestedOutputCount()).isEqualTo((short) 3);
        assertThat(detail.getValue().getEstimatedPromptTokens()).isNull();
        assertThat(detail.getValue().getOutputRatioSnapshot()).isNull();
    }

    @Test
    void videoReservationPersistsPricingSnapshotInSeparatedDetail() {
        BillingFixture fixture = fixture(50_000L);
        VideoProviderCostReservationMetering metering =
                new VideoProviderCostReservationMetering(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        AiConversationVideoResolution.P720,
                        6,
                        0,
                        0L,
                        1_400_000_000L,
                        100_000_000L,
                        0L,
                        8_400_000_000L);
        AiConversationReservationCommand command = new AiConversationReservationCommand(
                7L,
                null,
                reservationCommand().model(),
                new byte[32],
                metering);

        AiConversationReservation reservation = fixture.service().reserve(command);

        // 6 秒 × 0.14 美元/秒 = 0.84 美元，账户最小单位为 0.01，因此预扣 84。
        assertThat(reservation.reservedQuotaMinor()).isEqualTo(84L);
        assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(49_916L);
        verify(fixture.activationService()).activateIfDue(
                fixture.quota(),
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        ArgumentCaptor<AiModelUsageDetail> usageDetail =
                ArgumentCaptor.forClass(AiModelUsageDetail.class);
        verify(fixture.detailMapper()).insert(usageDetail.capture());
        assertThat(usageDetail.getValue().getRequestedOutputCount()).isNull();

        ArgumentCaptor<AiModelUsageVideoDetail> videoDetail =
                ArgumentCaptor.forClass(AiModelUsageVideoDetail.class);
        verify(fixture.videoDetailMapper()).insert(videoDetail.capture());
        assertThat(videoDetail.getValue().getUsageId()).containsExactly((byte) 2);
        assertThat(videoDetail.getValue().getVideoMode())
                .isEqualTo("TEXT_TO_VIDEO");
        assertThat(videoDetail.getValue().getVideoResolution()).isEqualTo("P720");
        assertThat(videoDetail.getValue().getRequestedDurationSeconds()).isEqualTo(6);
        assertThat(videoDetail.getValue().getEstimatedProviderCostTicks())
                .isEqualTo(8_400_000_000L);
    }

    private static AiConversationBillingServiceImpl service() {
        return new AiConversationBillingServiceImpl(
                mock(AiConversationMapper.class),
                mock(AiModelUsageMapper.class),
                mock(AiModelUsageDetailMapper.class),
                mock(AiModelUsageVideoDetailMapper.class),
                mock(UserMembershipQuotaMapper.class),
                mock(HybridSemaphoreIdWorker.class),
                new AiConversationQuotaCalculator(),
                new AiConversationProviderCostQuotaCalculator(),
                activationService(),
                mock(UserProfileCacheInvalidationExecutor.class),
                CLOCK,
                mock(AiConversationMetrics.class));
    }

    private static BillingFixture fixture(long balanceMinor) {
        return fixture(balanceMinor, MembershipQuotaLoadtestFaultGate.disabled());
    }

    private static BillingFixture fixture(
            long balanceMinor,
            MembershipQuotaLoadtestFaultGate faultGate) {
        AiConversationMapper conversationMapper = mock(AiConversationMapper.class);
        AiModelUsageMapper usageMapper = mock(AiModelUsageMapper.class);
        AiModelUsageDetailMapper detailMapper = mock(AiModelUsageDetailMapper.class);
        AiModelUsageVideoDetailMapper videoDetailMapper =
                mock(AiModelUsageVideoDetailMapper.class);
        UserMembershipQuotaMapper quotaMapper = mock(UserMembershipQuotaMapper.class);
        MembershipQuotaPeriodActivationService activationService =
                mock(MembershipQuotaPeriodActivationService.class);
        UserProfileCacheInvalidationExecutor cacheInvalidationExecutor =
                mock(UserProfileCacheInvalidationExecutor.class);
        HybridSemaphoreIdWorker idWorker = mock(HybridSemaphoreIdWorker.class);
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setMembershipTier(MembershipTier.FREE.ordinal());
        quota.setQuotaBalanceMinor(balanceMinor);
        quota.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        when(conversationMapper.insert(any())).thenReturn(1);
        when(quotaMapper.findByLoginIdentityIdForUpdate(7L)).thenReturn(quota);
        when(quotaMapper.updateBalanceAndPeriod(any())).thenReturn(1);
        when(usageMapper.insert(any())).thenReturn(1);
        when(detailMapper.insert(any())).thenReturn(1);
        when(videoDetailMapper.insert(any())).thenReturn(1);
        when(idWorker.nextId())
                .thenReturn(new byte[] {1})
                .thenReturn(new byte[] {2});
        AiConversationBillingServiceImpl service =
                new AiConversationBillingServiceImpl(
                        conversationMapper,
                        usageMapper,
                        detailMapper,
                        videoDetailMapper,
                        quotaMapper,
                        idWorker,
                        new AiConversationQuotaCalculator(),
                        new AiConversationProviderCostQuotaCalculator(),
                        activationService,
                        cacheInvalidationExecutor,
                        faultGate,
                        CLOCK,
                        mock(AiConversationMetrics.class));
        return new BillingFixture(
                service,
                quota,
                activationService,
                quotaMapper,
                usageMapper,
                detailMapper,
                videoDetailMapper,
                cacheInvalidationExecutor);
    }

    private static AiConversationReservationCommand reservationCommand() {
        AiModelCacheEntry model = new AiModelCacheEntry(
                9L,
                "test-model",
                "openai",
                "test model",
                "",
                List.of(),
                new BigDecimal("0.75"),
                new BigDecimal("0.075"),
                new BigDecimal("4.5"),
                256_000L,
                128_000L,
                List.of());
        return new AiConversationReservationCommand(
                7L,
                null,
                model,
                new byte[32],
                100L);
    }

    private static MembershipQuotaPeriodActivationServiceImpl activationService() {
        return new MembershipQuotaPeriodActivationServiceImpl(
                tier -> new MembershipQuotaPlan(
                        tier == MembershipTier.PLUS ? 200_000L : 5_000L,
                        Duration.ofDays(7)));
    }

    /**
     * 集中保存预扣测试使用的协作者，便于同时断言余额和未发生的持久化动作。
     */
    private record BillingFixture(
            AiConversationBillingServiceImpl service,
            UserMembershipQuota quota,
            MembershipQuotaPeriodActivationService activationService,
            UserMembershipQuotaMapper quotaMapper,
            AiModelUsageMapper usageMapper,
            AiModelUsageDetailMapper detailMapper,
            AiModelUsageVideoDetailMapper videoDetailMapper,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor) {
    }
}
