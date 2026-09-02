package com.example.temperate.service.user.membership.payment.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipSupersededCloseConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该单元测试是来约束被替换旧单只能执行第三方关单或支付事实对账，绝不能重新打开本地订单或发放权益。
 */
final class MembershipSupersededCloseConsumerServiceImplTest {

    private MembershipPaymentOrderLookupService lookupService;
    private MembershipOrderSnapshotStore orderStore;
    private MembershipPaymentProvider provider;
    private PaymentFactReconciliationService reconciliationService;
    private MembershipSupersededClosePublisher publisher;
    private MembershipSupersededCloseConsumerService service;

    @BeforeEach
    void setUp() {
        lookupService = mock(MembershipPaymentOrderLookupService.class);
        orderStore = mock(MembershipOrderSnapshotStore.class);
        provider = mock(MembershipPaymentProvider.class);
        MembershipPaymentProviderRegistry registry = mock(
                MembershipPaymentProviderRegistry.class);
        when(registry.getRequired(PaymentProviderType.LIUHAO)).thenReturn(provider);
        reconciliationService = mock(PaymentFactReconciliationService.class);
        publisher = mock(MembershipSupersededClosePublisher.class);
        service = new MembershipSupersededCloseConsumerServiceImpl(
                lookupService,
                orderStore,
                registry,
                reconciliationService,
                mock(MembershipPaymentAttemptTransactionService.class),
                new HybridBase64UrlCodec(),
                publisher,
                properties(),
                mock(MembershipPaymentMetrics.class));
    }

    @Test
    void providerConfirmedCloseCompletesWithoutChangingLocalTerminalOrder() {
        MembershipOrderSnapshot order = closedOrder();
        when(lookupService.find(order.orderId())).thenReturn(Optional.of(order));
        when(provider.closePayment(any())).thenReturn(new PaymentCloseResult(
                PaymentProviderStatus.CLOSED, order.providerTradeNo()));

        service.process(envelope(order.orderId(), 0));

        verify(provider).closePayment(any());
        verify(reconciliationService, never()).reconcilePaid(any(), any());
        verify(publisher, never()).publish(anyString(), anyInt(), any());
        assertThat(order.status()).isEqualTo(MembershipOrderStatus.CLOSED);
    }

    @Test
    void providerPaidDuringCloseIsReconciledIntoExistingRefundFlow() {
        MembershipOrderSnapshot order = closedOrder();
        PaymentQueryResult paid = new PaymentQueryResult(
                order.orderId(),
                order.providerTradeNo(),
                "channel-trade",
                PaymentProviderStatus.PAID,
                order.payAmountYuan(),
                order.updatedAt(),
                null);
        when(lookupService.find(order.orderId())).thenReturn(Optional.of(order));
        when(provider.closePayment(any())).thenReturn(new PaymentCloseResult(
                PaymentProviderStatus.PAID, order.providerTradeNo()));
        when(provider.queryPayment(any())).thenReturn(paid);
        when(reconciliationService.reconcilePaid(order, paid)).thenReturn(true);

        service.process(envelope(order.orderId(), 0));

        verify(reconciliationService).reconcilePaid(order, paid);
        verify(publisher, never()).publish(anyString(), anyInt(), any());
    }

    @Test
    void uncertainProviderResultSchedulesABoundedRetry() {
        MembershipOrderSnapshot order = closedOrder();
        when(lookupService.find(order.orderId())).thenReturn(Optional.of(order));
        when(provider.closePayment(any())).thenReturn(new PaymentCloseResult(
                PaymentProviderStatus.UNKNOWN, order.providerTradeNo()));
        when(provider.queryPayment(any())).thenReturn(PaymentQueryResult.unknown(order.orderId()));

        service.process(envelope(order.orderId(), 0));

        verify(publisher).publish(order.orderId(), 1, Duration.ofSeconds(30));
    }

    @Test
    void callbackMarkerHandsControlToCallbackWorkerWithoutCallingProvider() {
        MembershipOrderSnapshot order = closedOrder();
        when(lookupService.find(order.orderId())).thenReturn(Optional.of(order));
        when(orderStore.callbackInProgress(order.orderId())).thenReturn(true);

        service.process(envelope(order.orderId(), 0));

        verify(provider, never()).closePayment(any());
        verify(provider, never()).queryPayment(any());
    }

    private static MembershipPaymentRabbitEnvelope<MembershipSupersededCloseMessage> envelope(
            String orderId,
            int retryCount) {
        return new MembershipPaymentRabbitEnvelope<>(
                "AaAjECcaAQGqi_h2Rl1Piw",
                MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EVENT,
                MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                OffsetDateTime.parse("2026-08-20T12:00:00Z"),
                "trace-test",
                new MembershipSupersededCloseMessage(orderId, retryCount));
    }

    private static MembershipOrderSnapshot closedOrder() {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                "AaAjECcaAQGqi_h2Rl1PiA",
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "wxpay",
                MembershipOrderStatus.CLOSED,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "LIUHAO:TRADE:provider-trade",
                OffsetDateTime.parse("2026-08-20T11:55:00Z"),
                OffsetDateTime.parse("2026-08-20T12:05:00Z"),
                null,
                null,
                3L,
                OffsetDateTime.parse("2026-08-20T11:54:00Z"),
                OffsetDateTime.parse("2026-08-20T12:00:00Z"));
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        false, "", "", Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofSeconds(30),
                        Duration.ofMinutes(10), Duration.ofHours(6)),
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
