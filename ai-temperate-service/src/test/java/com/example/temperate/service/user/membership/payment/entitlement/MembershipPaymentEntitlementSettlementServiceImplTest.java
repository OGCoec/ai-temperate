package com.example.temperate.service.user.membership.payment.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallback;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.entitlement.impl.MembershipPaymentEntitlementSettlementServiceImpl;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 该测试是来约束支付成功后的订单、套餐额度、权益裁决和回调裁决必须在同一结算边界内完整成功或整体失败。
 */
final class MembershipPaymentEntitlementSettlementServiceImplTest {

    private static final HybridBase64UrlCodec CODEC = new HybridBase64UrlCodec();
    private static final OffsetDateTime PAID_AT =
            OffsetDateTime.parse("2028-01-31T10:00:00Z");
    private static final OffsetDateTime RESOLVED_AT =
            OffsetDateTime.parse("2028-01-31T10:00:01Z");
    private static final String ORDER_ID = encoded((byte) 1);
    private static final String CALLBACK_ID = encoded((byte) 2);

    private MembershipOrderMapper orderMapper;
    private MembershipPaymentCallbackMapper callbackMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private MembershipQuotaPlanService quotaPlanService;
    private UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private ObjectMapper objectMapper;
    private MembershipPaymentEntitlementSettlementService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(MembershipOrderMapper.class);
        callbackMapper = mock(MembershipPaymentCallbackMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        quotaPlanService = mock(MembershipQuotaPlanService.class);
        cacheInvalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new MembershipPaymentEntitlementSettlementServiceImpl(
                orderMapper,
                callbackMapper,
                quotaMapper,
                quotaPlanService,
                cacheInvalidationExecutor,
                CODEC,
                objectMapper);
    }

    @Test
    void appliedPaymentAtomicallyAdvancesOrderGrantsFullQuotaAndResolvesFacts()
            throws Exception {
        MembershipOrder databaseOrder = databaseOrder(MembershipOrderStatus.CLOSING, 1L);
        MembershipPaymentCallback callback = callback(null);
        UserMembershipQuota quota = quota();
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback));
        when(quotaMapper.findByLoginIdentityIdsForUpdate(List.of(17L)))
                .thenReturn(List.of(quota));
        when(quotaPlanService.getRequired(MembershipTier.PLUS))
                .thenReturn(new MembershipQuotaPlan(200_000L, Duration.ofDays(7)));
        when(orderMapper.batchAdvanceState(anyString())).thenReturn(1);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(1);
        when(orderMapper.batchResolveEntitlements(anyString())).thenReturn(1);
        when(callbackMapper.batchResolve(anyString())).thenReturn(1);

        service.settleApplied(List.of(command()));

        InOrder transactionOrder = inOrder(orderMapper, callbackMapper, quotaMapper);
        transactionOrder.verify(orderMapper).findByIdsJsonForUpdate(anyString());
        transactionOrder.verify(callbackMapper).findByIdsJsonForUpdate(anyString());
        transactionOrder.verify(quotaMapper)
                .findByLoginIdentityIdsForUpdate(List.of(17L));
        transactionOrder.verify(orderMapper).batchAdvanceState(anyString());
        transactionOrder.verify(quotaMapper).batchGrantPaidMemberships(anyString());
        transactionOrder.verify(orderMapper).batchResolveEntitlements(anyString());
        transactionOrder.verify(callbackMapper).batchResolve(anyString());

        ArgumentCaptor<String> grantJson = ArgumentCaptor.forClass(String.class);
        verify(quotaMapper).batchGrantPaidMemberships(grantJson.capture());
        JsonNode grant = objectMapper.readTree(grantJson.getValue()).get(0);
        assertThat(grant.get("loginIdentityId").asLong()).isEqualTo(17L);
        assertThat(grant.get("membershipTier").asInt())
                .isEqualTo(MembershipTier.PLUS.ordinal());
        assertThat(grant.get("quotaBalanceMinor").asLong()).isEqualTo(200_000L);
        assertThat(grant.get("quotaPeriodStartedAt").isNull()).isTrue();
        assertThat(grant.get("quotaPeriodEndsAt").asText())
                .isEqualTo("2028-01-31T10:00:00Z");
        assertThat(grant.get("membershipExpiresAt").asText())
                .isEqualTo("2028-02-29T10:00:00Z");
        verify(cacheInvalidationExecutor).evictAfterCommit(List.of(17L));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-08-22T10:00:00Z,2026-09-22T10:00:00Z",
        "2027-01-31T10:00:00Z,2027-02-28T10:00:00Z",
        "2028-01-31T10:00:00Z,2028-02-29T10:00:00Z"
    })
    void membershipExpirationUsesUtcNaturalMonthBoundaries(
            String paidAtValue,
            String expectedExpirationValue) throws Exception {
        OffsetDateTime paidAt = OffsetDateTime.parse(paidAtValue);
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder(
                        MembershipOrderStatus.CLOSING, 1L, paidAt)));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null, paidAt)));
        when(quotaMapper.findByLoginIdentityIdsForUpdate(List.of(17L)))
                .thenReturn(List.of(quota()));
        when(quotaPlanService.getRequired(MembershipTier.PLUS))
                .thenReturn(new MembershipQuotaPlan(200_000L, Duration.ofDays(7)));
        when(orderMapper.batchAdvanceState(anyString())).thenReturn(1);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(1);
        when(orderMapper.batchResolveEntitlements(anyString())).thenReturn(1);
        when(callbackMapper.batchResolve(anyString())).thenReturn(1);

        service.settleApplied(List.of(command(paidAt)));

        ArgumentCaptor<String> grantJson = ArgumentCaptor.forClass(String.class);
        verify(quotaMapper).batchGrantPaidMemberships(grantJson.capture());
        assertThat(objectMapper.readTree(grantJson.getValue())
                        .get(0)
                        .get("membershipExpiresAt")
                        .asText())
                .isEqualTo(expectedExpirationValue);
    }

    @Test
    void missingQuotaRowRollsBackBeforeAnyStateOrResolutionWrite() {
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder(MembershipOrderStatus.CLOSING, 1L)));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));
        when(quotaMapper.findByLoginIdentityIdsForUpdate(List.of(17L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.settleApplied(List.of(command())))
                .isInstanceOf(MembershipPaymentInfrastructureException.class);

        verify(orderMapper, never()).batchAdvanceState(anyString());
        verify(quotaMapper, never()).batchGrantPaidMemberships(anyString());
        verify(orderMapper, never()).batchResolveEntitlements(anyString());
        verify(callbackMapper, never()).batchResolve(anyString());
        verify(cacheInvalidationExecutor, never()).evictAfterCommit(List.of(17L));
    }

    @Test
    void higherDatabaseOrderVersionRejectsTheWholeSettlement() {
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder(MembershipOrderStatus.PAID, 3L)));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));

        assertThatThrownBy(() -> service.settleApplied(List.of(command())))
                .isInstanceOf(MembershipPaymentInfrastructureException.class);

        verify(quotaMapper, never()).findByLoginIdentityIdsForUpdate(List.of(17L));
        verify(callbackMapper, never()).batchResolve(anyString());
    }

    @Test
    void conflictingCallbackResolutionRejectsBeforeQuotaOrOrderWrites() {
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder(MembershipOrderStatus.CLOSING, 1L)));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback("REFUND_REQUIRED")));

        assertThatThrownBy(() -> service.settleApplied(List.of(command())))
                .isInstanceOf(MembershipPaymentInfrastructureException.class);

        verify(quotaMapper, never()).findByLoginIdentityIdsForUpdate(List.of(17L));
        verify(orderMapper, never()).batchAdvanceState(anyString());
        verify(orderMapper, never()).batchResolveEntitlements(anyString());
        verify(callbackMapper, never()).batchResolve(anyString());
    }

    @Test
    void previouslyAppliedEntitlementOnlyRepairsUnresolvedCallbackWithoutResettingQuota() {
        MembershipOrder applied = databaseOrder(MembershipOrderStatus.PAID, 2L);
        applied.setProviderTradeNo("provider-trade-1");
        applied.setPaidAt(PAID_AT);
        applied.setEntitlementResolution(MembershipOrderEntitlementResolution.APPLIED);
        applied.setEntitlementResolvedAt(RESOLVED_AT.minusSeconds(1));
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(applied));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));
        when(callbackMapper.batchResolve(anyString())).thenReturn(1);

        service.settleApplied(List.of(command()));

        verify(quotaMapper, never()).findByLoginIdentityIdsForUpdate(List.of(17L));
        verify(quotaMapper, never()).batchGrantPaidMemberships(anyString());
        verify(orderMapper, never()).batchResolveEntitlements(anyString());
        verify(callbackMapper).batchResolve(anyString());
        verify(cacheInvalidationExecutor, never()).evictAfterCommit(List.of(17L));
    }

    @Test
    void alreadyPersistedPaidStateStillReceivesExactlyOnePendingEntitlement() {
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(databaseOrder(MembershipOrderStatus.PAID, 2L)));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));
        when(quotaMapper.findByLoginIdentityIdsForUpdate(List.of(17L)))
                .thenReturn(List.of(quota()));
        when(quotaPlanService.getRequired(MembershipTier.PLUS))
                .thenReturn(new MembershipQuotaPlan(200_000L, Duration.ofDays(7)));
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(1);
        when(orderMapper.batchResolveEntitlements(anyString())).thenReturn(1);
        when(callbackMapper.batchResolve(anyString())).thenReturn(1);

        service.settleApplied(List.of(command()));

        verify(orderMapper, never()).batchAdvanceState(anyString());
        verify(quotaMapper).batchGrantPaidMemberships(anyString());
        verify(orderMapper).batchResolveEntitlements(anyString());
        verify(callbackMapper).batchResolve(anyString());
    }

    @Test
    void refundResolutionCommitsOrderAndCallbackBeforeAnyExternalRefund() throws Exception {
        MembershipOrder cancelled = databaseOrder(MembershipOrderStatus.CANCELLED, 3L);
        cancelled.setEntitlementResolution(MembershipOrderEntitlementResolution.NOT_GRANTED);
        cancelled.setEntitlementResolvedAt(RESOLVED_AT.minusMinutes(1));
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(cancelled));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));
        when(orderMapper.batchResolveEntitlements(anyString())).thenReturn(1);
        when(callbackMapper.batchResolve(anyString())).thenReturn(1);

        service.settleRefundRequired(List.of(
                new MembershipPaymentRefundEntitlementCommand(
                        CALLBACK_ID,
                        ORDER_ID,
                        RESOLVED_AT)));

        ArgumentCaptor<String> entitlementJson = ArgumentCaptor.forClass(String.class);
        InOrder transactionOrder = inOrder(orderMapper, callbackMapper);
        transactionOrder.verify(orderMapper).findByIdsJsonForUpdate(anyString());
        transactionOrder.verify(callbackMapper).findByIdsJsonForUpdate(anyString());
        transactionOrder.verify(orderMapper).batchResolveEntitlements(entitlementJson.capture());
        transactionOrder.verify(callbackMapper).batchResolve(anyString());
        assertThat(objectMapper.readTree(entitlementJson.getValue())
                        .get(0)
                        .get("providerTradeNo")
                        .isNull())
                .isTrue();
        verify(quotaMapper, never()).batchGrantPaidMemberships(anyString());
    }

    @Test
    void terminalNotGrantedCannotBeConvertedIntoAppliedEntitlement() {
        MembershipOrder closed = databaseOrder(MembershipOrderStatus.CLOSED, 3L);
        closed.setEntitlementResolution(MembershipOrderEntitlementResolution.NOT_GRANTED);
        closed.setEntitlementResolvedAt(RESOLVED_AT.minusMinutes(1));
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(closed));
        when(callbackMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(List.of(callback(null)));

        assertThatThrownBy(() -> service.settleApplied(List.of(command())))
                .isInstanceOf(MembershipPaymentInfrastructureException.class);

        verify(quotaMapper, never()).batchGrantPaidMemberships(anyString());
        verify(orderMapper, never()).batchResolveEntitlements(anyString());
        verify(callbackMapper, never()).batchResolve(anyString());
    }

    private static MembershipPaymentEntitlementCommand command() {
        return command(PAID_AT);
    }

    private static MembershipPaymentEntitlementCommand command(
            OffsetDateTime paidAt) {
        return new MembershipPaymentEntitlementCommand(
                CALLBACK_ID,
                paidSnapshot(paidAt),
                paidAt.plusSeconds(1));
    }

    private static MembershipOrderSnapshot paidSnapshot() {
        return paidSnapshot(PAID_AT);
    }

    private static MembershipOrderSnapshot paidSnapshot(OffsetDateTime paidAt) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PAID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "provider-trade-1",
                paidAt.minusMinutes(1),
                paidAt.plusMinutes(4),
                paidAt.plusMinutes(9),
                paidAt,
                2L,
                paidAt.minusMinutes(1),
                paidAt.plusSeconds(1));
    }

    private static MembershipOrder databaseOrder(
            MembershipOrderStatus status,
            long stateVersion) {
        return databaseOrder(status, stateVersion, PAID_AT);
    }

    private static MembershipOrder databaseOrder(
            MembershipOrderStatus status,
            long stateVersion,
            OffsetDateTime paidAt) {
        MembershipOrderSnapshot desired = paidSnapshot(paidAt);
        MembershipOrder order = new MembershipOrder();
        order.setId(CODEC.decode(ORDER_ID));
        order.setLoginIdentityId(desired.loginIdentityId());
        order.setMembershipTier(desired.membershipTier());
        order.setPayAmountYuan(desired.payAmountYuan());
        order.setPayType(desired.payType());
        order.setStatus(status);
        order.setIdempotencyKey(desired.idempotencyKey());
        order.setProviderTradeNo(
                status == MembershipOrderStatus.PAID ? desired.providerTradeNo() : null);
        order.setPaymentStartedAt(desired.paymentStartedAt());
        order.setExpiresAt(desired.expiresAt());
        order.setClosingDeadlineAt(desired.closingDeadlineAt());
        order.setPaidAt(status == MembershipOrderStatus.PAID ? desired.paidAt() : null);
        order.setStateVersion(stateVersion);
        order.setCreatedAt(desired.createdAt());
        order.setUpdatedAt(desired.updatedAt());
        return order;
    }

    private static MembershipPaymentCallback callback(String resolution) {
        return callback(resolution, PAID_AT);
    }

    private static MembershipPaymentCallback callback(
            String resolution,
            OffsetDateTime paidAt) {
        MembershipPaymentCallback callback = new MembershipPaymentCallback();
        callback.setId(CODEC.decode(CALLBACK_ID));
        callback.setOrderId(CODEC.decode(ORDER_ID));
        callback.setProviderTradeNo("provider-trade-1");
        callback.setTradeStatus("TRADE_SUCCESS");
        callback.setPaidAmountYuan(new BigDecimal("20.00"));
        callback.setPaidAt(paidAt);
        callback.setReceivedAt(paidAt);
        callback.setResolution(resolution);
        callback.setResolvedAt(resolution == null ? null : paidAt.plusSeconds(1));
        return callback;
    }

    private static UserMembershipQuota quota() {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setId(9L);
        quota.setLoginIdentityId(17L);
        quota.setMembershipTier(MembershipTier.GO.ordinal());
        quota.setQuotaBalanceMinor(123L);
        quota.setQuotaPeriodStartedAt(PAID_AT.minusDays(2));
        quota.setQuotaPeriodEndsAt(PAID_AT.plusDays(5));
        quota.setMembershipExpiresAt(PAID_AT.plusDays(20));
        return quota;
    }

    private static String encoded(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return CODEC.encode(id);
    }
}
