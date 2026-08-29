package com.example.temperate.service.user.apichat.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiModelApiUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.ai.entity.AiModelApiUsage;
import com.example.temperate.model.ai.entity.AiModelApiUsageDetail;
import com.example.temperate.model.ai.entity.ApiKeyReservationAuthorization;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.billing.impl.AiConversationQuotaCalculator;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.membership.MembershipQuotaPeriodActivationService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 该测试是来锁定外部 API 预扣、差额结算、余额不足转待对账、系统退款和 RESERVED 条件幂等边界。
 */
final class ApiChatBillingServiceImplTest {

    private static final byte[] API_KEY_ID = hybridId(1);
    private static final byte[] USAGE_ID = hybridId(2);
    private static final byte[] DETAIL_ID = hybridId(3);
    private static final byte[] DIGEST = new byte[32];
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);

    private UserApiKeyMapper apiKeyMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private AiModelApiUsageMapper usageMapper;
    private AiModelApiUsageDetailMapper detailMapper;
    private UserProfileCacheInvalidationExecutor invalidationExecutor;
    private MembershipQuotaPeriodActivationService quotaPeriodActivationService;
    private HybridSemaphoreIdWorker idWorker;
    private ApiChatBillingServiceImpl service;

    @BeforeEach
    void setUp() {
        apiKeyMapper = mock(UserApiKeyMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        usageMapper = mock(AiModelApiUsageMapper.class);
        detailMapper = mock(AiModelApiUsageDetailMapper.class);
        invalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        quotaPeriodActivationService = mock(MembershipQuotaPeriodActivationService.class);
        idWorker = mock(HybridSemaphoreIdWorker.class);
        when(idWorker.nextId()).thenReturn(USAGE_ID.clone(), DETAIL_ID.clone());
        service = new ApiChatBillingServiceImpl(
                apiKeyMapper,
                quotaMapper,
                usageMapper,
                detailMapper,
                new AiConversationQuotaCalculator(),
                quotaPeriodActivationService,
                invalidationExecutor,
                idWorker,
                CLOCK,
                new SimpleMeterRegistry());
    }

    @Test
    void reserveRevalidatesAuthorizationAndCreatesOneUsageDetailPair()
            throws Exception {
        ApiKeyPrincipal principal = principal();
        ValidatedApiChatRequest request = validatedRequest();
        UserMembershipQuota quota = quota(10L);
        when(apiKeyMapper.findReservationAuthorizationForUpdate(API_KEY_ID, 23L))
                .thenReturn(authorization());
        when(apiKeyMapper.touchLastUsed(eq(API_KEY_ID), any())).thenReturn(1);
        when(quotaMapper.findByLoginIdentityIdForUpdate(17L)).thenReturn(quota);
        when(quotaMapper.updateBalanceAndPeriod(quota)).thenReturn(1);
        when(usageMapper.insert(any(AiModelApiUsage.class))).thenReturn(1);
        when(detailMapper.insert(any(AiModelApiUsageDetail.class))).thenReturn(1);

        ApiInferenceReservation reservation = service.reserve(principal, request);

        assertThat(reservation.usageId()).containsExactly(USAGE_ID);
        assertThat(reservation.reservedMinor()).isEqualTo(2L);
        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(8L);
        verify(quotaPeriodActivationService).activateIfDue(
                quota,
                OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
        ArgumentCaptor<AiModelApiUsageDetail> detail =
                ArgumentCaptor.forClass(AiModelApiUsageDetail.class);
        verify(detailMapper).insert(detail.capture());
        assertThat(detail.getValue().getId()).containsExactly(DETAIL_ID);
        assertThat(detail.getValue().getUsageId()).containsExactly(USAGE_ID);
        assertThat(detail.getValue().getReservedQuotaMinor()).isEqualTo(2L);
        verify(invalidationExecutor).evictAfterCommit(17L);
    }

    @Test
    void failedAuthorizationNeverStartsTheQuotaPeriod() throws Exception {
        when(apiKeyMapper.findReservationAuthorizationForUpdate(API_KEY_ID, 23L))
                .thenReturn(null);

        assertThatThrownBy(() -> service.reserve(principal(), validatedRequest()))
                .isInstanceOf(ApiChatException.class);

        verify(quotaPeriodActivationService, never()).activateIfDue(any(), any());
        verify(quotaMapper, never()).findByLoginIdentityIdForUpdate(17L);
    }

    @Test
    void settlementNeverMakesBalanceNegativeAndUsesReconcileRequired()
            throws Exception {
        ApiInferenceReservation reservation = reservation();
        AiModelApiUsage persisted = persisted(AiModelBillingStatus.RESERVED);
        UserMembershipQuota quota = quota(0L);
        when(usageMapper.findByIdForUpdate(USAGE_ID)).thenReturn(persisted);
        when(quotaMapper.findByLoginIdentityIdForUpdate(17L)).thenReturn(quota);
        when(quotaMapper.updateBalanceAndPeriod(quota)).thenReturn(1);
        when(usageMapper.settle(
                eq(USAGE_ID),
                eq(AiModelBillingStatus.RESERVED.code()),
                eq(AiModelBillingStatus.RECONCILE_REQUIRED.code()),
                eq(0L),
                eq(2_400L),
                eq(0L),
                eq(2L),
                eq("STOP"),
                isNull(),
                any())).thenReturn(1);
        when(detailMapper.finalizeDetail(USAGE_ID, 1L)).thenReturn(1);

        service.settle(reservation, new ApiInferenceUsage(0, 2_400, 0), "STOP");

        assertThat(quota.getQuotaBalanceMinor()).isZero();
        verify(usageMapper).settle(
                eq(USAGE_ID),
                eq(AiModelBillingStatus.RESERVED.code()),
                eq(AiModelBillingStatus.RECONCILE_REQUIRED.code()),
                eq(0L),
                eq(2_400L),
                eq(0L),
                eq(2L),
                eq("STOP"),
                isNull(),
                any());
    }

    @Test
    void responsesReservationPersistsActualNonStreamingMode() throws Exception {
        ApiKeyPrincipal principal = principal();
        AiModelCacheEntry model = validatedRequest().model();
        UserMembershipQuota quota = quota(10L);
        when(apiKeyMapper.findReservationAuthorizationForUpdate(API_KEY_ID, 23L))
                .thenReturn(authorization());
        when(apiKeyMapper.touchLastUsed(eq(API_KEY_ID), any())).thenReturn(1);
        when(quotaMapper.findByLoginIdentityIdForUpdate(17L)).thenReturn(quota);
        when(quotaMapper.updateBalanceAndPeriod(quota)).thenReturn(1);
        when(usageMapper.insert(any(AiModelApiUsage.class))).thenReturn(1);
        when(detailMapper.insert(any(AiModelApiUsageDetail.class))).thenReturn(1);

        ApiInferenceReservation reservation = service.reserve(
                principal,
                new ApiInferenceExecutionRequest(
                        model, 300, 800, false, ApiInferenceProtocol.RESPONSES));

        ArgumentCaptor<AiModelApiUsageDetail> detail =
                ArgumentCaptor.forClass(AiModelApiUsageDetail.class);
        verify(detailMapper).insert(detail.capture());
        assertThat(detail.getValue().getStream()).isFalse();
        assertThat(reservation.protocol()).isEqualTo(ApiInferenceProtocol.RESPONSES);
    }

    @Test
    void systemFailureRefundsTheReservationExactlyOnce() {
        ApiInferenceReservation reservation = reservation();
        UserMembershipQuota quota = quota(5L);
        when(usageMapper.findByIdForUpdate(USAGE_ID))
                .thenReturn(persisted(AiModelBillingStatus.RESERVED));
        when(quotaMapper.findByLoginIdentityIdForUpdate(17L)).thenReturn(quota);
        when(quotaMapper.updateBalanceAndPeriod(quota)).thenReturn(1);
        when(usageMapper.settle(
                eq(USAGE_ID),
                eq(AiModelBillingStatus.RESERVED.code()),
                eq(AiModelBillingStatus.FAILED_REFUNDED.code()),
                isNull(),
                isNull(),
                isNull(),
                eq(0L),
                eq("SYSTEM_FAILURE_REFUNDED"),
                eq("UPSTREAM_UNAVAILABLE"),
                any())).thenReturn(1);
        when(detailMapper.finalizeDetail(USAGE_ID, -2L)).thenReturn(1);

        service.refundSystemFailure(reservation, "UPSTREAM_UNAVAILABLE");

        assertThat(quota.getQuotaBalanceMinor()).isEqualTo(7L);
        verify(invalidationExecutor).evictAfterCommit(17L);
    }

    @Test
    void duplicateTerminalCallbackDoesNotTouchQuotaAgain() {
        when(usageMapper.findByIdForUpdate(USAGE_ID))
                .thenReturn(persisted(AiModelBillingStatus.SETTLED));

        service.refundSystemFailure(reservation(), "UPSTREAM_UNAVAILABLE");

        verify(quotaMapper, never()).findByLoginIdentityIdForUpdate(17L);
        verify(detailMapper, never()).finalizeDetail(eq(USAGE_ID), anyLong());
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(API_KEY_ID, 17L, DIGEST, "B".repeat(43), Set.of(23L));
    }

    private static ApiInferenceReservation reservation() {
        return new ApiInferenceReservation(
                USAGE_ID,
                17L,
                API_KEY_ID,
                2L,
                800L,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                ApiInferenceProtocol.CHAT_COMPLETIONS);
    }

    private static UserMembershipQuota quota(long balance) {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(17L);
        quota.setMembershipTier(0);
        quota.setQuotaBalanceMinor(balance);
        quota.setQuotaPeriodStartedAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        quota.setQuotaPeriodEndsAt(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        return quota;
    }

    private static AiModelApiUsage persisted(AiModelBillingStatus status) {
        AiModelApiUsage usage = new AiModelApiUsage();
        usage.setId(USAGE_ID.clone());
        usage.setBillingStatus(status.code());
        return usage;
    }

    private static ApiKeyReservationAuthorization authorization() {
        ApiKeyReservationAuthorization authorization =
                new ApiKeyReservationAuthorization();
        authorization.setApiKeyId(API_KEY_ID.clone());
        authorization.setLoginIdentityId(17L);
        authorization.setKeyDigest(DIGEST);
        authorization.setKeyStatus(1);
        authorization.setAccountStatus(0);
        authorization.setAiModelId(23L);
        authorization.setModelName("gpt-test");
        authorization.setVendor("openai");
        authorization.setModelEnabled(true);
        authorization.setMappingStatus(1);
        authorization.setInputRatio(BigDecimal.ONE);
        authorization.setCachedInputRatio(BigDecimal.ONE);
        authorization.setOutputRatio(BigDecimal.ONE);
        authorization.setContextWindowTokens(8_192L);
        authorization.setMaxOutputTokens(1_024L);
        return authorization;
    }

    private static ValidatedApiChatRequest validatedRequest() throws Exception {
        ApiChatRequest request = new ObjectMapper().readValue("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],"stream":true}
                """, ApiChatRequest.class);
        return new ValidatedApiChatRequest(
                request,
                new AiModelCacheEntry(
                        23L,
                        "gpt-test",
                        "openai",
                        "test",
                        null,
                        List.of(),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        8_192,
                        1_024,
                        List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                300,
                800,
                false);
    }

    private static byte[] hybridId(int suffix) {
        byte[] id = new byte[16];
        id[15] = (byte) suffix;
        return id;
    }
}
