package com.example.temperate.service.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipClosingCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipPaymentCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

/**
 * 该单元测试是来约束 RabbitMQ 全阶段先执行回调 marker 门禁、中间阶段不主动访问支付方，以及最终阶段安全收敛或进入 DLQ。
 */
final class MembershipPaymentCheckConsumerServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = id((byte) 2);
    private static final String MESSAGE_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);

    private MembershipPaymentOrderLookupService lookupService;
    private MembershipOrderSnapshotStore orderStore;
    private MembershipPaymentProvider provider;
    private MembershipPaymentProviderRegistry providerRegistry;
    private PaymentFactReconciliationService reconciliationService;
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
    void pendingFinalStageQueriesThenStartsClosingAndSchedulesFinalClosingCheck() {
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
        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), 0);
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

        InOrder ordered = inOrder(reconciliationService, orderStore, finalCheckScheduler);
        ordered.verify(reconciliationService).reconcilePaid(any(), any());
        ordered.verify(orderStore).startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        ordered.verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), 0);
        verify(orderStore, never()).markPaid(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1, 30000",
        "1, 2, 60000",
        "2, 3, 60000",
        "3, 4, 120000"
    })
    void everyClosingIntermediateStagePublishesItsConfiguredNextDelayWithoutQuery(
            int currentStage,
            int nextStage,
            long nextDelayMillis) {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));

        closingService().process(closingEnvelope(currentStage, 0));

        verify(closingPublisher).publishNext(
                ORDER_ID, nextStage, 0, Duration.ofMillis(nextDelayMillis));
        verify(provider, never()).closePayment(any());
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
                eq(ORDER_ID), any(PaymentProviderStatus.class), any(OffsetDateTime.class));
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
                eq(ORDER_ID), any(PaymentProviderStatus.class), any(OffsetDateTime.class));
    }

    @Test
    void closingFinalPaidQueryRestoresCallbackReadyAndKeepsOrderClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.PAID, "123456789"));
        when(provider.queryPayment(any())).thenReturn(paidProvider());
        when(reconciliationService.reconcilePaid(any(), any())).thenReturn(true);

        closingService().process(closingEnvelope(4, 0));

        InOrder ordered = inOrder(provider, reconciliationService, closingPublisher);
        ordered.verify(provider).closePayment(any());
        ordered.verify(provider).queryPayment(any());
        ordered.verify(reconciliationService).reconcilePaid(any(), any());
        ordered.verify(closingPublisher).publishNext(
                ORDER_ID, 4, 1, Duration.ofSeconds(30));
        verify(orderStore, never()).finalizeClosing(
                org.mockito.ArgumentMatchers.eq(ORDER_ID),
                any(),
                org.mockito.ArgumentMatchers.eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    @Test
    void unknownFinalStatusRetriesThreeTimesThenThrowsForDlqWithoutClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.UNKNOWN, null));
        MembershipClosingCheckConsumerService service = closingService();

        service.process(closingEnvelope(4, 0));
        verify(closingPublisher).publishNext(
                ORDER_ID, 4, 1, Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.process(closingEnvelope(4, 3)))
                .isInstanceOf(MembershipPaymentTerminalQueryExhaustedException.class);
        verify(orderStore, never()).finalizeClosing(
                org.mockito.ArgumentMatchers.eq(ORDER_ID),
                any(),
                org.mockito.ArgumentMatchers.eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    @Test
    void explicitClosedWithoutMarkerAllowsFinalClosing() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, "123456789"));
        when(orderStore.finalizeClosing(
                ORDER_ID, PaymentProviderStatus.CLOSED, now)).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verify(orderStore).finalizeClosing(
                ORDER_ID, PaymentProviderStatus.CLOSED, now);
    }

    @Test
    void callbackMarkerCreatedDuringProviderCloseStopsWithoutTerminalRetry() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(provider.closePayment(any())).thenReturn(
                new PaymentCloseResult(PaymentProviderStatus.CLOSED, "123456789"));
        when(orderStore.finalizeClosing(
                ORDER_ID, PaymentProviderStatus.CLOSED, now)).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.CALLBACK_IN_PROGRESS,
                        MembershipOrderStatus.CLOSING,
                        2L));

        closingService().process(closingEnvelope(4, 0));

        verify(provider).closePayment(any());
        verify(provider, never()).queryPayment(any());
        verify(orderStore).finalizeClosing(
                ORDER_ID, PaymentProviderStatus.CLOSED, now);
        verifyNoInteractions(closingPublisher);
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
    void closingFinalMessageBeforeDeadlineReschedulesWithoutClosingProvider() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot early = orderWithDeadlines(
                MembershipOrderStatus.CLOSING,
                now.minusMinutes(5),
                now.plusNanos(1_000));
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(early));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);

        closingService().process(closingEnvelope(4, 2));

        verify(finalCheckScheduler).scheduleClosing(
                ORDER_ID, early.closingDeadlineAt(), 2);
        verify(provider, never()).closePayment(any());
        verify(orderStore, never()).finalizeClosing(anyString(), any(), any());
    }

    private MembershipPaymentCheckConsumerService pendingService() {
        return new MembershipPaymentCheckConsumerServiceImpl(
                lookupService,
                orderStore,
                providerRegistry,
                reconciliationService,
                paymentPublisher,
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
                "123456789",
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
                        Duration.ofSeconds(30),
                        3));
    }
}
