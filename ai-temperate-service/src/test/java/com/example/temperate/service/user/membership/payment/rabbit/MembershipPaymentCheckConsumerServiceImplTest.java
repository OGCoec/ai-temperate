package com.example.temperate.service.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionOutcome;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderTransitionResult;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentStatusQueryService;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipClosingCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipPaymentCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.PaymentCallbackQueue;
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
import org.mockito.InOrder;

/**
 * 该单元测试是来约束 RabbitMQ 中间阶段不主动查询、最终阶段才查询，以及 UNKNOWN 耗尽后保持 CLOSING 并进入 DLQ。
 */
final class MembershipPaymentCheckConsumerServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ORDER_ID = id((byte) 2);
    private static final String MESSAGE_ID = id((byte) 3);
    private static final String CALLBACK_ID = id((byte) 4);

    private MembershipPaymentOrderLookupService lookupService;
    private MembershipOrderSnapshotStore orderStore;
    private SimulatedPaymentStatusQueryService queryService;
    private PaymentCallbackQueue callbackQueue;
    private MembershipPaymentCheckPublisher paymentPublisher;
    private MembershipClosingCheckPublisher closingPublisher;
    private MembershipPaymentProperties properties;

    @BeforeEach
    void setUp() {
        lookupService = mock(MembershipPaymentOrderLookupService.class);
        orderStore = mock(MembershipOrderSnapshotStore.class);
        queryService = mock(SimulatedPaymentStatusQueryService.class);
        callbackQueue = mock(PaymentCallbackQueue.class);
        paymentPublisher = mock(MembershipPaymentCheckPublisher.class);
        closingPublisher = mock(MembershipClosingCheckPublisher.class);
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
        verify(queryService, never()).query(ORDER_ID);
    }

    @Test
    void pendingFinalStageQueriesThenStartsClosingAndPublishesFirstClosingCheck() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(queryService.query(ORDER_ID)).thenReturn(provider(
                SimulatedPaymentProviderStatus.UNPAID));
        when(orderStore.startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        verify(queryService).query(ORDER_ID);
        verify(closingPublisher).publishNext(
                ORDER_ID, 0, 0, Duration.ofSeconds(30));
    }

    @Test
    void pendingFinalPaidQueryOnlyEnsuresUnifiedCallbackReadyBeforeClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.PENDING_PAYMENT)));
        when(queryService.query(ORDER_ID)).thenReturn(paidProvider());
        when(orderStore.startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSING,
                        2L));

        pendingService().process(paymentEnvelope(8));

        InOrder ordered = inOrder(callbackQueue, orderStore, closingPublisher);
        ordered.verify(callbackQueue).ensureReady(CALLBACK_ID, NOW.toEpochMilli());
        ordered.verify(orderStore).startClosing(
                ORDER_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        ordered.verify(closingPublisher).publishNext(
                ORDER_ID, 0, 0, Duration.ofSeconds(30));
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
        verify(queryService, never()).query(ORDER_ID);
    }

    @Test
    void closingFinalPaidQueryRestoresCallbackReadyAndKeepsOrderClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(queryService.query(ORDER_ID)).thenReturn(paidProvider());
        when(callbackQueue.ensureReady(CALLBACK_ID, NOW.toEpochMilli()))
                .thenReturn(true);

        closingService().process(closingEnvelope(4, 0));

        InOrder ordered = inOrder(queryService, callbackQueue, closingPublisher);
        ordered.verify(queryService).query(ORDER_ID);
        ordered.verify(callbackQueue).ensureReady(CALLBACK_ID, NOW.toEpochMilli());
        ordered.verify(closingPublisher).publishNext(
                ORDER_ID, 4, 1, Duration.ofSeconds(30));
        verify(orderStore, never()).finalizeClosing(
                ORDER_ID, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownFinalStatusRetriesThreeTimesThenThrowsForDlqWithoutClosing() {
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(queryService.query(ORDER_ID)).thenReturn(provider(
                SimulatedPaymentProviderStatus.UNKNOWN));
        MembershipClosingCheckConsumerService service = closingService();

        service.process(closingEnvelope(4, 0));
        verify(closingPublisher).publishNext(
                ORDER_ID, 4, 1, Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.process(closingEnvelope(4, 3)))
                .isInstanceOf(MembershipPaymentTerminalQueryExhaustedException.class);
        verify(orderStore, never()).finalizeClosing(ORDER_ID, OffsetDateTime.ofInstant(
                NOW, ZoneOffset.UTC));
    }

    @Test
    void explicitUnpaidWithoutMarkerIsTheOnlyFinalClosingPath() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(lookupService.find(ORDER_ID)).thenReturn(Optional.of(order(
                MembershipOrderStatus.CLOSING)));
        when(orderStore.callbackInProgress(ORDER_ID)).thenReturn(false);
        when(queryService.query(ORDER_ID)).thenReturn(provider(
                SimulatedPaymentProviderStatus.UNPAID));
        when(orderStore.finalizeClosing(ORDER_ID, now)).thenReturn(
                new MembershipOrderTransitionResult(
                        MembershipOrderTransitionOutcome.APPLIED,
                        MembershipOrderStatus.CLOSED,
                        3L));

        closingService().process(closingEnvelope(4, 0));

        verify(orderStore).finalizeClosing(ORDER_ID, now);
    }

    private MembershipPaymentCheckConsumerService pendingService() {
        return new MembershipPaymentCheckConsumerServiceImpl(
                lookupService,
                orderStore,
                queryService,
                callbackQueue,
                paymentPublisher,
                closingPublisher,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MembershipPaymentMetrics.class));
    }

    private MembershipClosingCheckConsumerService closingService() {
        return new MembershipClosingCheckConsumerServiceImpl(
                lookupService,
                orderStore,
                queryService,
                callbackQueue,
                closingPublisher,
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
        return new MembershipOrderSnapshot(
                1,
                ORDER_ID,
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                null,
                now.minusMinutes(5),
                status == MembershipOrderStatus.CLOSING ? now : null,
                null,
                status == MembershipOrderStatus.CLOSING ? 2L : 1L,
                now.minusMinutes(10),
                now.minusMinutes(5));
    }

    private static SimulatedPaymentProviderResult provider(
            SimulatedPaymentProviderStatus status) {
        return new SimulatedPaymentProviderResult(
                1,
                ORDER_ID,
                status,
                null,
                null,
                null,
                null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private static SimulatedPaymentProviderResult paidProvider() {
        return new SimulatedPaymentProviderResult(
                1,
                ORDER_ID,
                SimulatedPaymentProviderStatus.PAID,
                CALLBACK_ID,
                "provider-trade-1",
                "alipay",
                new BigDecimal("20.00"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
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
