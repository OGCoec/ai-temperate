package com.example.temperate.service.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipClosingFinalizationSource;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipClosingCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipPaymentCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 该单元测试是来约束 RabbitMQ 关单时序：订单进入 CLOSING 后立即关闭支付方，但必须等到最终边界才允许写入 CLOSED。
 */
@ExtendWith(OutputCaptureExtension.class)
final class MembershipPaymentCheckConsumerServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = id((byte) 2);
    private static final String MESSAGE_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);
    private static final String UNBOUND_TRADE_REFERENCE = null;
    private static final String LIUHAO_TRADE_REFERENCE =
            "LIUHAO:TRADE:2026083108542370629";

    private MembershipPaymentOrderLookupService lookupService;
    private MembershipOrderSnapshotStore orderStore;
    private MembershipPaymentProvider provider;
    private MembershipPaymentProviderRegistry providerRegistry;
    private PaymentFactReconciliationService reconciliationService;
    private MembershipPaymentAttemptTransactionService transactionService;
    private MembershipPaymentCheckPublisher paymentPublisher;
    private MembershipClosingCheckPublisher closingPublisher;
    private MembershipPaymentFinalCheckScheduler finalCheckScheduler;
    private MembershipPaymentProperties properties;

    @BeforeEach
    void setUp() {
        lookupService = mock(MembershipPaymentOrderLookupService.class);
        orderStore = mock(MembershipOrderSnapshotStore.class);
        provider = mock(MembershipPaymentProvider.class);
        providerRegistry = mock(MembershipPaymentProviderRegistry.class);
        reconciliationService = mock(PaymentFactReconciliationService.class);
        transactionService = mock(MembershipPaymentAttemptTransactionService.class);
        when(providerRegistry.getRequired(any())).thenReturn(provider);
        paymentPublisher = mock(MembershipPaymentCheckPublisher.class);
        closingPublisher = mock(MembershipClosingCheckPublisher.class);
        finalCheckScheduler = mock(MembershipPaymentFinalCheckScheduler.class);
        properties = properties();
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1, 10000",
        "1, 2, 10000",
        "2, 3, 15000",
        "3, 4, 15000",
        "4, 5, 30000",
        "5, 6, 30000",
        "6, 7, 60000",
        "7, 8, 120000"
    })
    void everyPendingIntermediateStagePublishesItsConfiguredNextDelay(
            int currentStage,
            int nextStage,
            long nextDelayMillis) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        MembershipPaymentCheckConsumerService service = pendingService();

        service.process(paymentEnvelope(currentStage));

        verify(paymentPublisher).publishNext(
                ORDER_ID, nextStage, Duration.ofMillis(nextDelayMillis));
        verify(provider, never()).queryPayment(any());
    }

    @Test
    void startedLiuhaoIntermediateStageBindsTradeReferenceWithoutWaitingForExpiry() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot pending = startedLiuhaoOrder(now.plusMinutes(4));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(pending));
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                null,
                PaymentProviderStatus.PENDING,
                new BigDecimal("20.00"),
                null,
                null));
        when(orderStore.patchProviderTradeNo(
                ORDER_ID, pending.loginIdentityId(), LIUHAO_TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.APPLIED);

        pendingService().process(paymentEnvelope(0));

        verify(provider).queryPayment(any());
        verify(transactionService).bindProviderTradeNo(
                eq(pending.loginIdentityId()), any(byte[].class), eq(LIUHAO_TRADE_REFERENCE));
        verify(orderStore).patchProviderTradeNo(
                ORDER_ID, pending.loginIdentityId(), LIUHAO_TRADE_REFERENCE);
        verify(finalCheckScheduler).schedulePending(ORDER_ID, pending.expiresAt());
        verify(paymentPublisher, never()).publishNext(anyString(), anyInt(), any());
    }

    @Test
    void startedLiuhaoOrderNotYetVisibleKeepsDiscoveryChainAndLogsReason(
            CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot pending = startedLiuhaoOrder(now.plusMinutes(4));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(pending));
        when(provider.queryPayment(any())).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID,
                "sensitive provider response"));

        pendingService().process(paymentEnvelope(0));

        verify(paymentPublisher).publishNext(ORDER_ID, 1, Duration.ofSeconds(10));
        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .contains("provider_query_outcome=failed")
                .contains("reason=PROVIDER_QUERY_FAILED")
                .doesNotContain("sensitive provider response");
    }

    @Test
    void missingRedisSnapshotIsLoggedAndRestoredAfterDatabaseBinding(
            CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot pending = startedLiuhaoOrder(now.plusMinutes(4));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(pending));
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                null,
                PaymentProviderStatus.PENDING,
                new BigDecimal("20.00"),
                null,
                null));
        when(orderStore.patchProviderTradeNo(
                ORDER_ID, pending.loginIdentityId(), LIUHAO_TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.MISSING);
        when(orderStore.putAndGet(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        pendingService().process(paymentEnvelope(0));

        verify(orderStore).putAndGet(any());
        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .contains("redis_bind=missing")
                .contains("reason=REDIS_BIND_MISSING")
                .contains("event=provider_trade_reference_bound");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void everyPendingStageStopsWhenCallbackMarkerExists(int stage) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(true);

        pendingService().process(paymentEnvelope(stage));

        InOrder ordered = inOrder(lookupService, orderStore);
        ordered.verify(lookupService).find(ORDER_ID);
        ordered.verify(orderStore).callbackInProgress(ORDER_ID);
        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                paymentPublisher, closingPublisher, finalCheckScheduler);
        verify(orderStore, never()).startClosing(anyString(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = MembershipOrderStatus.class,
            names = {"CLOSING", "PAID", "CANCELLED", "CLOSED"})
    void pendingConsumerDoesNotReadMarkerForInactiveOrder(
            MembershipOrderStatus status) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(status)));

        pendingService().process(paymentEnvelope(0));

        verify(orderStore, never()).callbackInProgress(ORDER_ID);
        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                paymentPublisher, closingPublisher, finalCheckScheduler);
    }

    @Test
    void pendingMarkerReadFailurePropagatesBeforePublishingOrQuerying() {
        MembershipPaymentInfrastructureException failure =
                new MembershipPaymentInfrastructureException("redis unavailable");
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> pendingService().process(paymentEnvelope(8)))
                .isSameAs(failure);

        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                paymentPublisher, closingPublisher, finalCheckScheduler);
        verify(orderStore, never()).startClosing(anyString(), any(), any());
    }

    @Test
    void pendingFinalStageQueriesThenStartsClosingAndPublishesImmediateClosingCheck() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.PENDING));
        when(orderStore.startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        verify(provider).queryPayment(any());
        verify(closingPublisher).publishNext(
                ORDER_ID, 0, 0, Duration.ZERO);
        verify(finalCheckScheduler, never()).scheduleClosing(anyString(), any(), anyInt());
    }

    @Test
    void pendingClosingRaceThatWasAlreadyAppliedStillPublishesImmediateClosingCheck() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.PENDING));
        when(orderStore.startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.ALREADY_APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        verify(closingPublisher).publishNext(
                ORDER_ID, 0, 0, Duration.ZERO);
        verify(finalCheckScheduler, never()).scheduleClosing(anyString(), any(), anyInt());
    }

    @Test
    void pendingFinalPaidQueryOnlyEnsuresUnifiedCallbackReadyBeforeClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(provider.queryPayment(any())).thenReturn(paidProvider());
        when(orderStore.startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        InOrder ordered = inOrder(reconciliationService, orderStore, closingPublisher);
        ordered.verify(reconciliationService).reconcilePaid(any(), any());
        ordered.verify(orderStore).startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        ordered.verify(closingPublisher).publishNext(
                ORDER_ID, 0, 0, Duration.ZERO);
        verify(orderStore, never()).markPaid(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pendingQueryBindsResolvedLiuhaoTradeNumberBeforeStartingClosing() {
        MembershipOrderSnapshot pending = liuhaoOrderWithDeadlines(
                MembershipOrderStatus.PENDING_PAYMENT,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(5),
                null);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(pending));
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                null,
                PaymentProviderStatus.PENDING,
                new BigDecimal("0.05"),
                null,
                null));
        when(orderStore.patchProviderTradeNo(
                ORDER_ID, pending.loginIdentityId(), LIUHAO_TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.APPLIED);
        when(orderStore.startClosing(anyString(), any(), any()))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        verify(transactionService).bindProviderTradeNo(
                eq(pending.loginIdentityId()), any(byte[].class), eq(LIUHAO_TRADE_REFERENCE));
        verify(orderStore).patchProviderTradeNo(
                ORDER_ID, pending.loginIdentityId(), LIUHAO_TRADE_REFERENCE);
        verify(closingPublisher).publishNext(ORDER_ID, 0, 0, Duration.ZERO);
    }

    @Test
    void pendingQueryLogsControlledFailureCodeWithoutSensitiveProviderMessage(
            CapturedOutput output) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(provider.queryPayment(any())).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_TIMEOUT,
                "sensitive provider response"));
        when(orderStore.startClosing(anyString(), any(), any()))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .contains("LIUHAO_TIMEOUT")
                .doesNotContain("sensitive provider response");
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1, 30000",
        "1, 2, 60000",
        "2, 3, 60000",
        "3, 4, 120000"
    })
    void everyClosingIntermediateStageRetriesItsConfiguredNextDelayWhenCloseIsUnknown(
            int currentStage,
            int nextStage,
            long nextDelayMillis) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));

        closingService().process(closingEnvelope(currentStage, 0));

        verify(provider).closePayment(any());
        verify(closingPublisher).publishNext(
                ORDER_ID, nextStage, 0, Duration.ofMillis(nextDelayMillis));
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @Test
    void closingInitialSafeCloseSchedulesFinalBoundaryWithoutClosingLocalOrder() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));

        closingService().process(closingEnvelope(0, 0));

        verify(provider).closePayment(any());
        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, early.closingDeadlineAt(), 0);
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @Test
    void closingSafeResponseBindsResolvedLiuhaoTradeNumberWithoutClosingEarly() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = liuhaoOrderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                null,
                PaymentProviderStatus.PENDING,
                new BigDecimal("20.00"),
                null,
                null));
        when(orderStore.patchProviderTradeNo(
                ORDER_ID, early.loginIdentityId(), LIUHAO_TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.APPLIED);

        closingService().process(closingEnvelope(0, 0));

        verify(transactionService).bindProviderTradeNo(
                eq(early.loginIdentityId()), any(byte[].class), eq(LIUHAO_TRADE_REFERENCE));
        verify(orderStore).patchProviderTradeNo(
                ORDER_ID, early.loginIdentityId(), LIUHAO_TRADE_REFERENCE);
        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, early.closingDeadlineAt(), 0);
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @Test
    void closingTradeNumberConflictStaysNonTerminalAndRetries() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = liuhaoOrderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                null,
                PaymentProviderStatus.PENDING,
                new BigDecimal("20.00"),
                null,
                null));
        when(orderStore.patchProviderTradeNo(
                ORDER_ID, early.loginIdentityId(), LIUHAO_TRADE_REFERENCE))
                .thenReturn(MembershipProviderTradeNoPatchOutcome.CONFLICT);

        closingService().process(closingEnvelope(0, 0));

        verify(closingPublisher).publishNext(
                ORDER_ID, 1, 0, Duration.ofSeconds(30));
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
        verify(finalCheckScheduler, never()).scheduleClosing(anyString(), any(), anyInt());
    }

    @Test
    void closingInitialPaidResultReconcilesAndKeepsNormalClosingChecks() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.PAID, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(paidProvider());
        when(reconciliationService.reconcilePaid(any(), any())).thenReturn(true);

        closingService().process(closingEnvelope(0, 0));

        InOrder ordered = inOrder(provider, reconciliationService, closingPublisher);
        ordered.verify(provider).closePayment(any());
        ordered.verify(provider).queryPayment(any());
        ordered.verify(reconciliationService).reconcilePaid(any(), any());
        ordered.verify(closingPublisher).publishNext(
                ORDER_ID, 1, 0, Duration.ofSeconds(30));
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentProviderStatus.class,
            names = {"PENDING", "UNKNOWN"})
    void closingInitialUnsafeStatusKeepsOrderClosingAndRetries(
            PaymentProviderStatus status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(5));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(status, null));

        closingService().process(closingEnvelope(0, 0));

        verify(provider).closePayment(any());
        verify(closingPublisher).publishNext(
                ORDER_ID, 1, 0, Duration.ofSeconds(30));
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void everyClosingStageStopsWhenCallbackMarkerExists(int stage) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(true);

        closingService().process(closingEnvelope(stage, 3));

        InOrder ordered = inOrder(lookupService, orderStore);
        ordered.verify(lookupService).find(ORDER_ID);
        ordered.verify(orderStore).callbackInProgress(ORDER_ID);
        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                closingPublisher);
        verify(orderStore, never()).finalizeClosing(
                eq(ORDER_ID),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = MembershipOrderStatus.class,
            names = {"PENDING_PAYMENT", "PAID", "CANCELLED", "CLOSED"})
    void closingConsumerDoesNotReadMarkerForInactiveOrder(
            MembershipOrderStatus status) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(status)));

        closingService().process(closingEnvelope(0, 0));

        verify(orderStore, never()).callbackInProgress(ORDER_ID);
        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                closingPublisher);
    }

    @Test
    void closingMarkerReadFailurePropagatesBeforePublishingOrClosing() {
        MembershipPaymentInfrastructureException failure =
                new MembershipPaymentInfrastructureException("redis unavailable");
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> closingService().process(closingEnvelope(4, 0)))
                .isSameAs(failure);

        verifyNoInteractions(providerRegistry, provider, reconciliationService,
                closingPublisher);
        verify(orderStore, never()).finalizeClosing(
                eq(ORDER_ID),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @Test
    void unknownCloseAtFinalBoundaryQueriesPaidFactWithoutClosingOrder() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));
        when(provider.queryPayment(any())).thenReturn(paidProvider());
        when(reconciliationService.reconcilePaid(any(), any())).thenReturn(true);

        closingService().process(closingEnvelope(4, 0));

        InOrder ordered = inOrder(provider, reconciliationService);
        ordered.verify(provider).closePayment(any());
        ordered.verify(provider).queryPayment(any());
        ordered.verify(reconciliationService).reconcilePaid(any(), any());
        verifyNoInteractions(closingPublisher);
        verify(orderStore, never()).finalizeClosing(
                org.mockito.ArgumentMatchers.eq(ORDER_ID),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                org.mockito.ArgumentMatchers.eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    @Test
    void unknownCloseAtFinalBoundaryStillQueriesAndTimeoutCloses() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.UNKNOWN));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.UNKNOWN,
                MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        InOrder ordered = inOrder(provider, orderStore);
        ordered.verify(provider).closePayment(any());
        ordered.verify(provider).queryPayment(any());
        ordered.verify(orderStore).finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.UNKNOWN,
                MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
                now);
        verifyNoInteractions(closingPublisher);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentProviderStatus.class,
            names = {"CLOSED", "EXPIRED", "FAILED", "REFUNDED"})
    void providerConfirmedTerminalWithoutMarkerAllowsFinalClosing(
            PaymentProviderStatus providerStatus) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(providerStatus, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(provider(providerStatus));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                providerStatus,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now)).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verify(orderStore).finalizeClosing(
                ORDER_ID,
                providerStatus,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now);
        verify(provider).queryPayment(any());
    }

    @Test
    void pendingFinalQueryTimeoutClosesWithoutRetrying() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.PENDING));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.PENDING,
                MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verify(orderStore).finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.PENDING,
                MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
                now);
        verifyNoInteractions(closingPublisher);
    }

    @Test
    void callbackMarkerCreatedDuringProviderCloseStopsWithoutTerminalRetry() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.CLOSED));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now)).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS,
                        MembershipOrderStatus.CLOSING,
                        2L));

        closingService().process(closingEnvelope(4, 0));

        verify(provider).closePayment(any());
        verify(provider).queryPayment(any());
        verify(orderStore).finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now);
        verifyNoInteractions(closingPublisher);
    }

    @Test
    void closeSignatureFailureCannotSkipFinalQuery() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                "sensitive provider response"));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.CLOSED));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verify(provider).queryPayment(any());
        verify(orderStore).finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now);
    }

    @Test
    void finalQueryFailureTimeoutClosesWithoutLeakingProviderMessage(
            CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));
        when(provider.queryPayment(any())).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID,
                "sensitive provider response"));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.UNKNOWN,
                MembershipClosingFinalizationSource.TIMEOUT_UNCONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        assertThat(output.getAll())
                .contains("reason=FINALIZED_CLOSED_TIMEOUT_UNCONFIRMED")
                .doesNotContain("sensitive provider response")
                .doesNotContain(ORDER_ID);
        verifyNoInteractions(closingPublisher);
    }

    @Test
    void concurrentPaidTerminalWinsOverClosingFinalization() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, null));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.CLOSED));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.NOT_ALLOWED,
                        MembershipOrderStatus.PAID,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verifyNoInteractions(closingPublisher);
        verify(reconciliationService, never()).reconcilePaid(any(), any());
    }

    @Test
    void repeatedFinalMessageTreatsAlreadyClosedAsIdempotent() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, null));
        when(provider.queryPayment(any())).thenReturn(provider(PaymentProviderStatus.CLOSED));
        when(orderStore.finalizeClosing(
                ORDER_ID,
                PaymentProviderStatus.CLOSED,
                MembershipClosingFinalizationSource.PROVIDER_CONFIRMED,
                now)).thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.ALREADY_APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verifyNoInteractions(closingPublisher);
        verify(reconciliationService, never()).reconcilePaid(any(), any());
    }

    @Test
    void pendingFinalMessageBeforeExpiryReschedulesWithoutQueryingProvider() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.PENDING_PAYMENT,
                now.plusNanos(1_000),
                null);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);

        pendingService().process(paymentEnvelope(8));

        verify(finalCheckScheduler).schedulePending(ORDER_ID, early.expiresAt());
        verify(provider, never()).queryPayment(any());
        verify(orderStore, never()).startClosing(anyString(), any(), any());
    }

    @Test
    void closingFinalMessageBeforeDeadlineConfirmsProviderThenReschedulesBoundary() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusNanos(1_000));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(
                        PaymentProviderStatus.CLOSED, LIUHAO_TRADE_REFERENCE));

        closingService().process(closingEnvelope(4, 2));

        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, early.closingDeadlineAt(), 0);
        verify(provider).closePayment(any());
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    @Test
    void unknownAtLastStageBeforeDeadlineSchedulesExactFinalBoundary() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusMinutes(1));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));

        closingService().process(closingEnvelope(4, 2));

        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, early.closingDeadlineAt(), 2);
        verify(provider, never()).queryPayment(any());
        verify(orderStore, never()).finalizeClosing(
                anyString(),
                any(PaymentProviderStatus.class),
                any(MembershipClosingFinalizationSource.class),
                any(OffsetDateTime.class));
    }

    private MembershipPaymentCheckConsumerService pendingService() {
        return new MembershipPaymentCheckConsumerServiceImpl(
                lookupService,
                orderStore,
                providerRegistry,
                reconciliationService,
                transactionService,
                new HybridBase64UrlCodec(),
                paymentPublisher,
                closingPublisher,
                finalCheckScheduler,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
    }

    private MembershipClosingCheckConsumerService closingService() {
        return new MembershipClosingCheckConsumerServiceImpl(
                lookupService,
                orderStore,
                providerRegistry,
                reconciliationService,
                transactionService,
                new HybridBase64UrlCodec(),
                closingPublisher,
                finalCheckScheduler,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage>
            paymentEnvelope(int stage) {
        return new MembershipPaymentRabbitEnvelope<>(
                MESSAGE_ID,
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                1,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "trace-test",
                new MembershipPaymentCheckMessage(ORDER_ID, stage));
    }

    private static MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage>
            closingEnvelope(int stage, int retries) {
        return new MembershipPaymentRabbitEnvelope<>(
                MESSAGE_ID,
                MembershipPaymentRabbitNames.CLOSING_EVENT,
                1,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "trace-test",
                new MembershipClosingCheckMessage(ORDER_ID, stage, retries));
    }

    private static MembershipOrderSnapshot order(MembershipOrderStatus status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return orderWithDeadlines(
                status,
                now.minusMinutes(5),
                status == MembershipOrderStatus.CLOSING ? now : null);
    }

    private static MembershipOrderSnapshot orderWithDeadlines(
            MembershipOrderStatus status,
            OffsetDateTime expiresAt,
            OffsetDateTime closingDeadlineAt) {
        return orderWithDeadlines(
                status, expiresAt, closingDeadlineAt, LIUHAO_TRADE_REFERENCE);
    }

    private static MembershipOrderSnapshot liuhaoOrderWithDeadlines(
            MembershipOrderStatus status,
            OffsetDateTime expiresAt,
            OffsetDateTime closingDeadlineAt) {
        return orderWithDeadlines(
                status, expiresAt, closingDeadlineAt, UNBOUND_TRADE_REFERENCE);
    }

    private static MembershipOrderSnapshot startedLiuhaoOrder(
            OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                MembershipOrderStatus.PENDING_PAYMENT,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                UNBOUND_TRADE_REFERENCE,
                now.minusSeconds(1),
                expiresAt,
                null,
                null,
                1L,
                now.minusMinutes(1),
                now.minusSeconds(1));
    }

    private static MembershipOrderSnapshot orderWithDeadlines(
            MembershipOrderStatus status,
            OffsetDateTime expiresAt,
            OffsetDateTime closingDeadlineAt,
            String providerTradeNo) {
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
                providerTradeNo,
                expiresAt,
                closingDeadlineAt,
                null,
                status == MembershipOrderStatus.CLOSING ? 2L : 1L,
                now.minusMinutes(10),
                now.minusMinutes(5));
    }

    private static PaymentQueryResult provider(
            PaymentProviderStatus status) {
        return new PaymentQueryResult(
                ORDER_ID,
                null,
                null,
                status,
                null,
                null,
                null);
    }

    private static PaymentQueryResult paidProvider() {
        return new PaymentQueryResult(
                ORDER_ID,
                LIUHAO_TRADE_REFERENCE,
                "BAR-P-123456790",
                PaymentProviderStatus.PAID,
                new BigDecimal("20.00"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                CALLBACK_ID);
    }

    private static String id(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return new HybridBase64UrlCodec().encode(bytes);
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                true,
                PaymentProviderType.LIUHAO,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Bar(
                        false,
                        URI.create("https://ihaveagoddamnplan.com"),
                        "",
                        0,
                        Map.of(),
                        null,
                        null,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        65_536),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3),
                new MembershipPaymentProperties.Liuhao(
                        true,
                        URI.create("https://liuhao.net"),
                        "1001",
                        "merchant-private-key",
                        "platform-public-key",
                        "merchant-public-key",
                        URI.create("https://niko000o.site/api/payment/liuhao/notify"),
                        URI.create("https://niko000o.site/pages/account/payment-result"),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        65_536,
                        Duration.ofMinutes(5)),
                List.of(PaymentProviderType.LIUHAO));
    }
}
