package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentCallbackDecisionServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackBatchServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementCommand;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementSettlementService;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentRefundEntitlementCommand;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentWorker;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.refund.MembershipRefundAttemptCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentUnappliedCallbackStore;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentMissingSnapshotReleaseOutcome;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 该单元测试是来约束第一个五秒任务必须先提交回调表再推进 Redis PAID，并在基础设施失败时精确重入 ready。
 */
@ExtendWith(OutputCaptureExtension.class)
final class PaymentCallbackBatchServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);

    private PaymentCallbackQueue callbackQueue;
    private MembershipOrderSnapshotStore orderStore;
    private MembershipPaymentUnappliedCallbackStore unappliedCallbackStore;
    private PaymentCallbackPersistenceService persistenceService;
    private MembershipPaymentEntitlementSettlementService entitlementService;
    private MembershipRefundAttemptCoordinator refundCoordinator;
    private MembershipPaymentRejectedCallbackResumeService rejectedResumeService;
    private MembershipPaymentLoadtestFaultGate loadtestFaultGate;
    private MembershipPaymentMetrics metrics;
    private PaymentCallbackBatchService service;
    private PaymentCallbackClaim claim;
    private PaymentCallbackSnapshot callback;

    @BeforeEach
    void setUp() {
        callbackQueue = mock(PaymentCallbackQueue.class);
        orderStore = mock(MembershipOrderSnapshotStore.class);
        unappliedCallbackStore = mock(MembershipPaymentUnappliedCallbackStore.class);
        persistenceService = mock(PaymentCallbackPersistenceService.class);
        entitlementService = mock(MembershipPaymentEntitlementSettlementService.class);
        refundCoordinator = mock(MembershipRefundAttemptCoordinator.class);
        rejectedResumeService = mock(MembershipPaymentRejectedCallbackResumeService.class);
        loadtestFaultGate = mock(MembershipPaymentLoadtestFaultGate.class);
        metrics = mock(MembershipPaymentMetrics.class);
        claim = new PaymentCallbackClaim(CALLBACK_ID, NOW.toEpochMilli());
        callback = callback();
        when(callbackQueue.claim(anyInt(), anyLong()))
                .thenReturn(List.of(claim), List.of());
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(orderStore.findAll(any())).thenReturn(Map.of(ORDER_ID, order()));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        2L)));
        when(unappliedCallbackStore.releaseRejected(any())).thenReturn(Set.of(CALLBACK_ID));
        service = new PaymentCallbackBatchServiceImpl(
                callbackQueue,
                orderStore,
                unappliedCallbackStore,
                mock(MembershipOrderMapper.class),
                persistenceService,
                entitlementService,
                new MembershipPaymentCallbackDecisionServiceImpl(properties()),
                refundCoordinator,
                rejectedResumeService,
                loadtestFaultGate,
                new HybridBase64UrlCodec(),
                new ObjectMapper(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                metrics);
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
    void reportsNaturalWorkerRunBatchAndClaimCounts() {
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(orderStore.markPaidAll(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.PAID,
                        2L)));

        service.flushOneRun();

        verify(metrics).workerRunCompleted(
                org.mockito.ArgumentMatchers.eq(MembershipPaymentWorker.CALLBACK),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("drained"),
                anyLong(),
                anyString());
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
                unappliedCallbackStore,
                mapper,
                persistenceService,
                entitlementService,
                new MembershipPaymentCallbackDecisionServiceImpl(properties()),
                mock(MembershipRefundAttemptCoordinator.class),
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
        verify(refundCoordinator).processInitial(anyString(), any(PaymentRefundCommand.class));
        verify(orderStore, never()).markPaidAll(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void refundRequiredClosingOrderFinalizesBeforeRefundWithoutRestartingMq() {
        MembershipOrderSnapshot closingOrder = lateClosingOrder();
        when(orderStore.findAll(any())).thenReturn(Map.of(ORDER_ID, closingOrder));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));

        service.flushOneRun();

        InOrder finalizationOrder = inOrder(
                entitlementService,
                unappliedCallbackStore,
                refundCoordinator,
                callbackQueue);
        finalizationOrder.verify(entitlementService).settleRefundRequired(any());
        finalizationOrder.verify(unappliedCallbackStore).finalizeRefundRequired(List.of(
                new MembershipPaymentRefundRequiredFinalizationCommand(
                        claim,
                        ORDER_ID,
                        callback.providerTradeNo(),
                        closingOrder.expiresAt().plus(properties().closingDuration()),
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
        finalizationOrder.verify(refundCoordinator)
                .processInitial(anyString(), any(PaymentRefundCommand.class));
        finalizationOrder.verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
        verify(rejectedResumeService, never()).resume(any());
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
                unappliedCallbackStore,
                rejectedResumeService,
                callbackQueue);
        rejectedOrder.verify(persistenceService).resolve(any());
        rejectedOrder.verify(unappliedCallbackStore).releaseRejected(List.of(
                new MembershipPaymentRejectedCallbackReleaseCommand(claim, ORDER_ID)));
        rejectedOrder.verify(rejectedResumeService).resume(order());
        rejectedOrder.verify(callbackQueue).complete(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @Test
    void recoveredRejectedCallbackReleasesMarkerBeforeRepublishingFinalStage() {
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REJECTED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));

        service.flushOneRun();

        InOrder order = inOrder(unappliedCallbackStore, rejectedResumeService, callbackQueue);
        order.verify(unappliedCallbackStore).releaseRejected(List.of(
                new MembershipPaymentRejectedCallbackReleaseCommand(claim, ORDER_ID)));
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

        verify(refundCoordinator, never()).processInitial(anyString(), any());
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

        verify(refundCoordinator).processInitial(CALLBACK_ID, new PaymentRefundCommand(
                ORDER_ID, callback.providerTradeNo(), callback.paidAmountYuan()));
        verify(entitlementService).settleRefundRequired(any());
        verify(unappliedCallbackStore).finalizeRefundRequired(any());
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alipay", "wxpay"})
    void recoveredRefundRequiredWithExistingEntitlementReappliesTerminalSettlement(
            String payType,
            CapturedOutput output) {
        callback = callback(
                new BigDecimal("20.00"), payType, "LIUHAO:TRADE:legacy-provider-trade");
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, legacyRefundRequiredOrder(payType, callback.providerTradeNo())));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        recovered.setOrderEntitlementResolution(
                MembershipOrderEntitlementResolution.REFUND_REQUIRED);
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        AtomicBoolean terminalSettlementReapplied = new AtomicBoolean();
        doAnswer(invocation -> {
                    terminalSettlementReapplied.set(true);
                    return null;
                })
                .when(entitlementService).settleRefundRequired(any());
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenAnswer(invocation -> {
                    MembershipPaymentRefundTerminalFact fact = refundTerminalFact();
                    if (!terminalSettlementReapplied.get()) {
                        fact.setOrderProviderTradeNo(callback.providerTradeNo());
                    }
                    return Map.of(CALLBACK_ID, fact);
                });
        when(unappliedCallbackStore.releaseMissingRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED));

        service.flushOneRun();

        InOrder order = inOrder(
                entitlementService,
                unappliedCallbackStore,
                persistenceService,
                refundCoordinator,
                callbackQueue);
        order.verify(entitlementService).settleRefundRequired(any());
        order.verify(unappliedCallbackStore).finalizeRefundRequired(any());
        order.verify(persistenceService).findRefundTerminalFacts(List.of(CALLBACK_ID));
        order.verify(refundCoordinator).processInitial(CALLBACK_ID, new PaymentRefundCommand(
                ORDER_ID, callback.providerTradeNo(), callback.paidAmountYuan()));
        order.verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
        verify(orderStore, never()).markPaidAll(any());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_recovery")
                .contains("provider=liuhao")
                .contains("callback_resolution=refund_required")
                .contains("order_entitlement_class=refund_required")
                .contains("order_provider_trade_present=true")
                .contains("recovery_action=reapply_terminal_settlement")
                .contains("recovery_outcome=started")
                .contains("reason=LEGACY_TERMINAL_REPAIR_REQUIRED")
                .contains("recovery_outcome=completed")
                .contains("reason=TERMINAL_SETTLEMENT_REAPPLIED")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo());
    }

    @Test
    void recoveredRefundTerminalSettlementFailureLogsSafeReasonAndRequeues(
            CapturedOutput output) {
        callback = callback(
                new BigDecimal("20.00"), "wxpay", "LIUHAO:TRADE:sensitive-provider-trade");
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, legacyRefundRequiredOrder("wxpay", callback.providerTradeNo())));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        recovered.setOrderEntitlementResolution(
                MembershipOrderEntitlementResolution.REFUND_REQUIRED);
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        doThrow(new IllegalStateException("sensitive settlement failure detail"))
                .when(entitlementService).settleRefundRequired(any());

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(unappliedCallbackStore, never()).finalizeRefundRequired(any());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        verify(callbackQueue, never()).complete(any());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_recovery")
                .contains("recovery_outcome=failed")
                .contains("reason=TERMINAL_SETTLEMENT_FAILED")
                .contains("event=membership_payment_callback_retry")
                .contains("failure_stage=refund_terminal_settlement")
                .contains("exception_class=IllegalStateException")
                .contains("count=1")
                .contains("retry_delay_ms=5000")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo())
                .doesNotContain("sensitive settlement failure detail");
    }

    @Test
    void missingRedisSnapshotUsesExactDatabaseTerminalFactBeforeRefunding(
            CapturedOutput output) {
        callback = callback(
                new BigDecimal("20.00"), "alipay", "LIUHAO:TRADE:provider-trade-1");
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of(CALLBACK_ID, refundTerminalFact()));
        when(unappliedCallbackStore.releaseMissingRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED));

        service.flushOneRun();

        verify(persistenceService).findRefundTerminalFacts(List.of(CALLBACK_ID));
        verify(unappliedCallbackStore).releaseMissingRefundRequired(any());
        verify(refundCoordinator).processInitial(CALLBACK_ID, new PaymentRefundCommand(
                ORDER_ID, callback.providerTradeNo(), callback.paidAmountYuan()));
        verify(callbackQueue).complete(List.of(
                new PaymentCallbackCompletion(
                        claim,
                        ORDER_ID,
                        PaymentProviderResultCompletionAction.RESET_UNPAID)));
        verify(callbackQueue, never()).requeue(any(), anyLong());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_preflight")
                .contains("provider=liuhao")
                .contains("stage=database_terminal_fact")
                .contains("snapshot_outcome=missing")
                .contains("order_provider_trade_present=false")
                .contains("verification_outcome=verified")
                .contains("reason=VERIFIED")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo());
    }

    @Test
    void missingSnapshotWithoutDatabaseTerminalFactIsRequeuedWithoutRefund(
            CapturedOutput output) {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of());

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(unappliedCallbackStore, never()).releaseMissingRefundRequired(any());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        verify(callbackQueue, never()).complete(any());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_preflight")
                .contains("fact_present=false")
                .contains("verification_outcome=failed")
                .contains("reason=CALLBACK_FACT_MISSING")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRefundTerminalFacts")
    void missingSnapshotRejectsEveryIncompleteDatabaseAuthorityFact(
            String description,
            Consumer<MembershipPaymentRefundTerminalFact> corrupt) {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        MembershipPaymentRefundTerminalFact fact = refundTerminalFact();
        corrupt.accept(fact);
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of(CALLBACK_ID, fact));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(unappliedCallbackStore, never()).releaseMissingRefundRequired(any());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        verify(callbackQueue, never()).complete(any());
    }

    @Test
    void missingSnapshotLogsBoundOrderProviderTradeBeforeRequeue(
            CapturedOutput output) {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        MembershipPaymentRefundTerminalFact fact = refundTerminalFact();
        fact.setOrderProviderTradeNo(callback.providerTradeNo());
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of(CALLBACK_ID, fact));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(unappliedCallbackStore, never()).releaseMissingRefundRequired(any());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_preflight")
                .contains("order_provider_trade_present=true")
                .contains("verification_outcome=failed")
                .contains("reason=ORDER_PROVIDER_TRADE_STILL_BOUND")
                .contains("event=membership_payment_callback_retry")
                .contains("failure_stage=refund_preflight")
                .contains("exception_class=IllegalStateException")
                .contains("retry_delay_ms=5000")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo());
    }

    @Test
    void missingSnapshotReleaseFailureIsLoggedAndRequeuedBeforeRefund(
            CapturedOutput output) {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of(CALLBACK_ID, refundTerminalFact()));
        when(unappliedCallbackStore.releaseMissingRefundRequired(any())).thenReturn(Map.of());

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        verify(callbackQueue, never()).complete(any());
        assertThat(output.getAll())
                .contains("event=membership_payment_refund_preflight")
                .contains("stage=missing_snapshot_release")
                .contains("verification_outcome=failed")
                .contains("reason=MISSING_SNAPSHOT_RELEASE_FAILED")
                .doesNotContain(ORDER_ID)
                .doesNotContain(callback.providerTradeNo());
    }

    @Test
    void missingSnapshotReleaseKeepsClaimRetryableWhenProviderRefundFails() {
        when(orderStore.findAll(any())).thenReturn(Map.of(
                ORDER_ID, order(MembershipOrderStatus.CANCELLED)));
        MembershipPaymentCallbackWriteResult recovered = write();
        recovered.setInserted(false);
        recovered.setDuplicate(true);
        recovered.setSameCallback(true);
        recovered.setResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        when(persistenceService.persist(any())).thenReturn(List.of(recovered));
        when(unappliedCallbackStore.finalizeRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.MISSING,
                        null,
                        0L)));
        when(persistenceService.findRefundTerminalFacts(List.of(CALLBACK_ID)))
                .thenReturn(Map.of(CALLBACK_ID, refundTerminalFact()));
        when(unappliedCallbackStore.releaseMissingRefundRequired(any())).thenReturn(Map.of(
                CALLBACK_ID,
                MembershipPaymentMissingSnapshotReleaseOutcome.RELEASED));
        doThrow(new IllegalStateException("provider unavailable"))
                .when(refundCoordinator).processInitial(anyString(), any());

        service.flushOneRun();

        verify(unappliedCallbackStore).releaseMissingRefundRequired(any());
        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(callbackQueue, never()).complete(any());
    }

    @Test
    void refundFinalizationFailureRequeuesBeforeExternalRefundAndCompletion() {
        MembershipOrderSnapshot closingOrder = lateClosingOrder();
        when(orderStore.findAll(any())).thenReturn(Map.of(ORDER_ID, closingOrder));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(unappliedCallbackStore.finalizeRefundRequired(any()))
                .thenThrow(new IllegalStateException("redis down"));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(refundCoordinator, never()).processInitial(anyString(), any());
        verify(callbackQueue, never()).complete(any());
    }

    private MembershipPaymentRefundTerminalFact refundTerminalFact() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        MembershipPaymentRefundTerminalFact fact = new MembershipPaymentRefundTerminalFact();
        fact.setCallbackId(codec.decode(CALLBACK_ID));
        fact.setOrderId(codec.decode(ORDER_ID));
        fact.setProviderTradeNo(callback.providerTradeNo());
        fact.setCallbackResolution(MembershipPaymentCallbackResolution.REFUND_REQUIRED.name());
        fact.setOrderStatus(MembershipOrderStatus.CANCELLED);
        fact.setOrderEntitlementResolution(
                MembershipOrderEntitlementResolution.REFUND_REQUIRED);
        fact.setOrderProviderTradeNo(null);
        return fact;
    }

    private static Stream<Arguments> invalidRefundTerminalFacts() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        return Stream.of(
                Arguments.of(
                        "callbackId mismatch",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setCallbackId(codec.decode(id((byte) 31)))),
                Arguments.of(
                        "orderId mismatch",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setOrderId(codec.decode(id((byte) 32)))),
                Arguments.of(
                        "providerTradeNo mismatch",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setProviderTradeNo("provider-mismatch")),
                Arguments.of(
                        "callback resolution incomplete",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setCallbackResolution(
                                        MembershipPaymentCallbackResolution.APPLIED.name())),
                Arguments.of(
                        "order status is not terminal",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setOrderStatus(MembershipOrderStatus.PAID)),
                Arguments.of(
                        "entitlement is not refund required",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setOrderEntitlementResolution(
                                        MembershipOrderEntitlementResolution.APPLIED)),
                Arguments.of(
                        "order provider trade remains bound",
                        (Consumer<MembershipPaymentRefundTerminalFact>) fact ->
                                fact.setOrderProviderTradeNo("provider-still-bound")));
    }

    @Test
    void rejectedReleaseFailureRequeuesBeforeRabbitResumeAndCompletion() {
        callback = callback(new BigDecimal("20.01"), "alipay");
        when(callbackQueue.findAll(any())).thenReturn(Map.of(CALLBACK_ID, callback));
        when(persistenceService.persist(any())).thenReturn(List.of(write()));
        when(unappliedCallbackStore.releaseRejected(any()))
                .thenThrow(new IllegalStateException("redis down"));

        service.flushOneRun();

        verify(callbackQueue).requeue(List.of(claim), NOW.toEpochMilli());
        verify(rejectedResumeService, never()).resume(any());
        verify(callbackQueue, never()).complete(any());
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
        verify(refundCoordinator, never()).processInitial(anyString(), any());
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
        return callback(paidAmountYuan, payType, "provider-trade-1");
    }

    private static PaymentCallbackSnapshot callback(
            BigDecimal paidAmountYuan,
            String payType,
            String providerTradeNo) {
        return new PaymentCallbackSnapshot(
                PaymentCallbackSnapshot.CURRENT_SCHEMA_VERSION,
                CALLBACK_ID,
                ORDER_ID,
                "merchant-test",
                providerTradeNo,
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

    private static MembershipOrderSnapshot legacyRefundRequiredOrder(
            String payType,
            String providerTradeNo) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                payType,
                MembershipOrderStatus.CANCELLED,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                providerTradeNo,
                null,
                now.plusMinutes(5),
                null,
                null,
                3L,
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
