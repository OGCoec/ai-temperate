package com.example.temperate.service.user.aiconversation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationCommand;
import com.example.temperate.service.user.aiconversation.billing.ProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
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

        service().activateExpiredPeriod(quota);

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
        active.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
        service().activateExpiredPeriod(active);
        assertThat(active.getQuotaBalanceMinor()).isEqualTo(4_200L);

        UserMembershipQuota invalid = new UserMembershipQuota();
        invalid.setMembershipTier(99);
        invalid.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-07-30T12:00:00Z"));
        assertThatThrownBy(() -> service().activateExpiredPeriod(invalid))
                .isInstanceOfSatisfying(AiConversationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AiConversationErrorCode.AI_QUOTA_RULE_MISSING));
    }

    @Test
    void freeBalanceCanReserveOneThirdOutputCeilingUsingMinorConversion() {
        BillingFixture fixture = fixture(5_000L);

        AiConversationReservation reservation =
                fixture.service().reserve(reservationCommand());

        assertThat(reservation.reservedQuotaMinor()).isEqualTo(241L);
        assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(4_759L);
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

    private static AiConversationBillingServiceImpl service() {
        return new AiConversationBillingServiceImpl(
                mock(AiConversationMapper.class),
                mock(AiModelUsageMapper.class),
                mock(AiModelUsageDetailMapper.class),
                mock(UserMembershipQuotaMapper.class),
                mock(HybridSemaphoreIdWorker.class),
                new AiConversationQuotaCalculator(),
                new AiConversationProviderCostQuotaCalculator(),
                tier -> new MembershipQuotaPlan(
                        tier == MembershipTier.PLUS ? 200_000L : 5_000L,
                        Duration.ofDays(7)),
                mock(UserProfileCacheInvalidationExecutor.class),
                CLOCK,
                mock(AiConversationMetrics.class));
    }

    private static BillingFixture fixture(long balanceMinor) {
        AiConversationMapper conversationMapper = mock(AiConversationMapper.class);
        AiModelUsageMapper usageMapper = mock(AiModelUsageMapper.class);
        AiModelUsageDetailMapper detailMapper = mock(AiModelUsageDetailMapper.class);
        UserMembershipQuotaMapper quotaMapper = mock(UserMembershipQuotaMapper.class);
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
        when(idWorker.nextId())
                .thenReturn(new byte[] {1})
                .thenReturn(new byte[] {2});
        AiConversationBillingServiceImpl service =
                new AiConversationBillingServiceImpl(
                        conversationMapper,
                        usageMapper,
                        detailMapper,
                        quotaMapper,
                        idWorker,
                        new AiConversationQuotaCalculator(),
                        new AiConversationProviderCostQuotaCalculator(),
                        tier -> new MembershipQuotaPlan(
                                5_000L,
                                Duration.ofDays(7)),
                        mock(UserProfileCacheInvalidationExecutor.class),
                        CLOCK,
                        mock(AiConversationMetrics.class));
        return new BillingFixture(
                service,
                quota,
                quotaMapper,
                usageMapper,
                detailMapper);
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

    /**
     * 集中保存预扣测试使用的协作者，便于同时断言余额和未发生的持久化动作。
     */
    private record BillingFixture(
            AiConversationBillingServiceImpl service,
            UserMembershipQuota quota,
            UserMembershipQuotaMapper quotaMapper,
            AiModelUsageMapper usageMapper,
            AiModelUsageDetailMapper detailMapper) {
    }
}
