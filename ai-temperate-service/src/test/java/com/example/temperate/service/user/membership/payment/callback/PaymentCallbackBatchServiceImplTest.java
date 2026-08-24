package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentCallbackDecisionServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackBatchServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementCommand;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementSettlementService;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentRefundEntitlementCommand;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 该单元测试是来约束第一个五秒任务必须先提交回调表再推进 Redis PAID，并在基础设施失败时精确重入 ready。
 */
final class PaymentCallbackBatchServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);

    private PaymentCallbackQueue callbackQueue;
    private MembershipOrderSnapshotStore orderStore;
    private PaymentCallbackPersistenceService persistenceService;
    private MembershipPaymentEntitlementSettlementService entitlementService;
    private MembershipPaymentRefundService refundService;
    private MembershipPaymentRejectedCallbackResumeService rejectedResumeService;
    private MembershipPaymentLoadtestFaultGate loadtestFaultGate;
    private PaymentCallbackBatchService service;
    private PaymentCallbackClaim claim;
    private PaymentCallbackSnapshot callback;

    @BeforeEach
    void setUp() {
        callbackQueue = mock(PaymentCallbackQueue.class);
        orderStore = mock(MembershipOrderSnapshotStore.class);
        persistenceService = mock(PaymentCallbackPersistenceService.class);
        entitlementService = mock(MembershipPaymentEntitlementSettlementService.class);
        refundService = mock(MembershipPaymentRefundService.class);
        rejectedResumeService = mock(MembershipPaymentRejectedCallbackResumeService.class);
        loadtestFaultGate = mock(MembershipPaymentLoadtestFaultGate.class);
        claim = new PaymentCallbackClaim(CALLBACK_ID, NOW.toEpochMilli());
        callback = callback();
        when(callbackQueue.claim(anyInt(), anyLong()))
                .thenReturn(List.of(claim), List.of());
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(orderStore.findAll(any())).thenReturn(Map.of(ORDER_ID, order()));
        service = new PaymentCallbackBatchServiceImpl(
                callbackQueue,
                orderStore,
                mock(MembershipOrderMapper.class),
                persistenceService,
                entitlementService,
                new MembershipPaymentCallbackDecisionServiceImpl(properties()),
                refundService,
                rejectedResumeService,
                loadtestFaultGate,
                new HybridBase64UrlCodec(),
                new ObjectMapper(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
    }

    @Test
    void callbackRowCommitsBeforePaidLuaAndThenCompletesClaim() {
        MembershipPaymentCallbackWriteResult write = write();
        when(persistenceService.persist(any())).thenReturn(List.of(write));
        when(orderStore.markPaidAll(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.PAID,
                        2L)));

        service.flushOneRun();

        InOrder order = inOrder(
                persistenceService,
                entitlementService,
                orderStore,
                callbackQueue);
        order.verify(persistenceService).persist(any());
        order.verify(orderStore).markPaidAll(any());
        order.verify(entitlementService).settleApplied(any());
        order.verify(persistenceService).resolve(any());
        order.verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(claim, ORDER_ID)));
    }

    @Test
    void databaseFailureRequeuesExactClaimAndKeepsCallbackData() {
        when(persistenceService.persist(any())).thenThrow(new IllegalStateException("db down"));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(callbackQueue, never()).complete(any());
        verify(orderStore, never()).markPaidAll(any());
    }

    @Test
    void heldCallbackReturnsToReadyWithoutPersistingOrCompleting() {
        when(loadtestFaultGate.callbackHeld(ORDER_ID)).thenReturn(true);

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli() + 1_000L);
        verify(persistenceService, never()).persist(any());
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue, never()).complete(any());
    }

    @Test
    void injectedFailureAfterResolutionRequeuesBeforeRedisComplete() {
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(orderStore.markPaidAll(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.PAID,
                        2L)));
        doThrow(new IllegalStateException("injected"))
                .when(loadtestFaultGate)
                .failBeforeCallbackCompleteIfArmed(any());

        service.flushOneRun();

        verify(persistenceService).resolve(any());
        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(callbackQueue, never()).complete(any());
    }

    @Test
    void entitlementTransactionFailureKeepsMarkerAndDoesNotResolveCallback() {
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(orderStore.markPaidAll(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.PAID,
                        2L)));
        doThrow(new IllegalStateException("quota row missing"))
                .when(entitlementService)
                .settleApplied(any());

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(persistenceService, never()).resolve(any());
        verify(callbackQueue, never()).complete(any());
    }

    @Test
    void resolvedAppliedCallbackWithAppliedEntitlementOnlyCompletesRedisCleanup() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, paidOrder()));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.APPLIED.name());
        recovered.setOrderEntitlementResolution(
                MembershipOrderEntitlementResolution.APPLIED);
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));

        service.flushOneRun();

        verify(entitlementService, never()).settleApplied(any());
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(claim, ORDER_ID)));
    }

    @Test
    void historicalPaidEntitlementIsNeverGrantedByCallbackRecovery() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, paidOrder()));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.APPLIED.name());
        recovered.setOrderEntitlementResolution(
                MembershipOrderEntitlementResolution.LEGACY_NOT_GRANTED);
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));

        service.flushOneRun();

        verify(entitlementService, never()).settleApplied(any());
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(claim, ORDER_ID)));
    }

    @Test
    void missingCallbackHashIsCompletedAsCorruptWithoutDatabaseWrite() {
        when(callbackQueue.findAll(any())).thenReturn(Map.of());

        service.flushOneRun();

        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(claim, null)));
        verify(persistenceService, never()).persist(any());
    }

    @Test
    void orderDisappearingBeforePaidLuaRequeuesAndStopsCurrentRun() {
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(orderStore.markPaidAll(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(callbackQueue, times(1)).claim(anyInt(), anyLong());
    }

    @Test
    void persistedPaidOrderCompletesDuplicateWithoutRebuildingTerminalRedisState() {
        MembershipOrderSnapshot paidOrder = new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PAID,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "provider-trade-1",
                OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC),
                null,
                OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
                2L,
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(orderStore.findAll(any())).thenReturn(Map.of());
        MembershipOrderMapper mapper = mock(MembershipOrderMapper.class);
        com.example.temperate.model.user.membership.payment.MembershipOrder persisted =
                new com.example.temperate.model.user.membership.payment.MembershipOrder();
        persisted.setId(new HybridBase64UrlCodec().decode(ORDER_ID));
        persisted.setLoginIdentityId(paidOrder.loginIdentityId());
        persisted.setMembershipTier(paidOrder.membershipTier());
        persisted.setPayAmountYuan(paidOrder.payAmountYuan());
        persisted.setPayType(paidOrder.payType());
        persisted.setStatus(paidOrder.status());
        persisted.setIdempotencyKey(paidOrder.idempotencyKey());
        persisted.setProviderTradeNo(paidOrder.providerTradeNo());
        persisted.setExpiresAt(paidOrder.expiresAt());
        persisted.setPaidAt(paidOrder.paidAt());
        persisted.setStateVersion(paidOrder.stateVersion());
        persisted.setCreatedAt(paidOrder.createdAt());
        persisted.setUpdatedAt(paidOrder.updatedAt());
        when(mapper.findByIdsJson(anyString())).thenReturn(List.of(persisted));
        PaymentCallbackBatchService terminalService = new PaymentCallbackBatchServiceImpl(
                callbackQueue,
                orderStore,
                mapper,
                persistenceService,
                entitlementService,
                new MembershipPaymentCallbackDecisionServiceImpl(properties()),
                mock(MembershipPaymentRefundService.class),
                mock(MembershipPaymentRejectedCallbackResumeService.class),
                mock(MembershipPaymentLoadtestFaultGate.class),
                new HybridBase64UrlCodec(),
                new ObjectMapper(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
        MembershipPaymentCallbackWriteResult duplicate = write();
        duplicate.setInserted(false);
        duplicate.setDuplicate(true);
        duplicate.setSameCallback(true);
        when(persistenceService.persist(any())).thenReturn(List.of(duplicate));

        terminalService.flushOneRun();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MembershipPaymentEntitlementCommand>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(entitlementService).settleApplied(captor.capture());
        assertThat(captor.getValue())
                .extracting(command -> command.paidOrder().status())
                .containsExactly(MembershipOrderStatus.PAID);
        assertThat(captor.getValue().getFirst().paidOrder().stateVersion())
                .isEqualTo(2L);
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(claim, ORDER_ID)));
    }

    @Test
    void firstCancelledOrderCallbackPersistsRefundResolutionAndTriggersOnce() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));

        service.flushOneRun();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MembershipPaymentRefundEntitlementCommand>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(entitlementService).settleRefundRequired(captor.capture());
        assertThat(captor.getValue())
                .extracting(MembershipPaymentRefundEntitlementCommand::orderId)
                .containsExactly(ORDER_ID);
        verify(persistenceService).resolve(List.of());
        verify(refundService).refund(any(PaymentRefundCommand.class));
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void refundRequiredClosingOrderRepublishesFinalStageBeforeRemovingMarker() {
        MembershipOrderSnapshot closingOrder = lateClosingOrder();
        when(orderStore.findAll(any())).thenReturn(Map.of(ORDER_ID, closingOrder));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));

        service.flushOneRun();

        verify(entitlementService).settleRefundRequired(any());
        verify(refundService).refund(any(PaymentRefundCommand.class));
        InOrder recoveryOrder = inOrder(rejectedResumeService, callbackQueue);
        recoveryOrder.verify(rejectedResumeService).resume(closingOrder);
        recoveryOrder.verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void wrongAmountStillPersistsUniqueAuditAndResolvesRejected() {
        callback = callback(new BigDecimal("20.01"), "alipay");
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));

        service.flushOneRun();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PaymentCallbackResolutionCommand>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(persistenceService).resolve(captor.capture());
        assertThat(captor.getValue())
                .extracting(PaymentCallbackResolutionCommand::resolution)
                .containsExactly(MembershipPaymentCallbackResolution.REJECTED);
        verify(orderStore, never()).markPaidAll(any());
        InOrder rejectedOrder = inOrder(
                persistenceService,
                rejectedResumeService,
                callbackQueue);
        rejectedOrder.verify(persistenceService).resolve(any());
        rejectedOrder.verify(rejectedResumeService).resume(order());
        rejectedOrder.verify(callbackQueue).complete(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void recoveredRejectedCallbackRepublishesFinalStageBeforeRemovingMarker() {
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REJECTED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));

        service.flushOneRun();

        InOrder order = inOrder(rejectedResumeService, callbackQueue);
        order.verify(rejectedResumeService).resume(order());
        order.verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
        verify(orderStore, never()).markPaidAll(any());
    }

    @Test
    void resolvedDatabaseDuplicateDoesNotTriggerRefundAgain() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult duplicate = write();
        duplicate.setInserted(false);
        duplicate.setDuplicate(true);
        duplicate.setSameCallback(false);
        duplicate.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(duplicate));

        service.flushOneRun();

        verify(refundService, never()).refund(any());
        verify(orderStore, never()).markPaidAll(any());
    }

    @Test
    void recoveredOriginalRefundCallbackExecutesIdempotentRefundAgain() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));

        service.flushOneRun();

        verify(refundService).refund(new PaymentRefundCommand(
                ORDER_ID, callback.providerTradeNo(), callback.paidAmountYuan()));
        verify(entitlementService).settleRefundRequired(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void laterCallbackForTheSamePaidOrderIsCompletedWithoutResolutionOrRefund() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.PAID)));
        MembershipPaymentCallbackWriteResult duplicate = write();
        duplicate.setInserted(false);
        duplicate.setDuplicate(true);
        duplicate.setSameCallback(false);
        when(persistenceService.persist(any())).thenReturn(List.of(duplicate));

        service.flushOneRun();

        verify(persistenceService).resolve(List.of());
        verify(refundService, never()).refund(any());
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.REMOVE)));
    }

    private static PaymentCallbackSnapshot callback() {
        return callback(new BigDecimal("20.00"), "alipay");
    }

    private static PaymentCallbackSnapshot callback(
            BigDecimal paidAmountYuan,
            String payType) {
        return new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                CALLBACK_ID,
                ORDER_ID,
                "merchant-test",
                "provider-trade-1",
                "channel-trade-1",
                payType,
                "TRADE_SUCCESS",
                paidAmountYuan,
                OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                NOW.getEpochSecond(),
                "oO1u7d8uvVC8w3fXbDgMDca8gTkO1HLQ_U-HtMxVQ0A",
                "r6J7mFDrb9KH83hLrUkYQgt4AAJwxBkLBzsP4efjEKk");
    }

    private static MembershipOrderSnapshot order() {
        return order(MembershipOrderStatus.PENDING_PAYMENT);
    }

    private static MembershipOrderSnapshot paidOrder() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
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
                now.minusSeconds(10),
                now.plusMinutes(5),
                null,
                now.minusSeconds(5),
                2L,
                now,
                now);
    }

    private static MembershipOrderSnapshot order(MembershipOrderStatus status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                null,
                status == MembershipOrderStatus.CANCELLED ? null : now.minusSeconds(10),
                now.plusMinutes(5),
                null,
                null,
                status == MembershipOrderStatus.CANCELLED ? 3L : 1L,
                now,
                now);
    }

    private static MembershipOrderSnapshot lateClosingOrder() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.minusMinutes(5);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.CLOSING,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "provider-trade-1",
                expiresAt.minusMinutes(1),
                expiresAt,
                now,
                null,
                2L,
                expiresAt.minusMinutes(2),
                expiresAt);
    }

    private static MembershipPaymentCallbackWriteResult write() {
        MembershipPaymentCallbackWriteResult result =
                new MembershipPaymentCallbackWriteResult();
        result.setOrdinal(1);
        result.setInserted(true);
        result.setDuplicate(false);
        result.setSameCallback(true);
        result.setOrderMismatch(false);
        result.setPersistedCallbackId(new HybridBase64UrlCodec().decode(CALLBACK_ID));
        return result;
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30), 3));
    }
}
