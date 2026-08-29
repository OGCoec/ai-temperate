package com.example.temperate.service.user.membership.payment.provider.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.store.SimulatedPaymentProviderResultStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来验证本地模拟支付事实到统一 Provider 查询结果的边界映射，避免虚构 BAR 渠道字段或丢失支付完成时间。
 */
class LocalSimulatedMembershipPaymentProviderImplTest {

    private static final String ORDER_ID = "AaAjECcaAQGqi_h2Rl1PiA";
    private static final String CALLBACK_ID = "AaAjECcaAQGqi_h2Rl1Piw";

    @Test
    void createsNoExternalBrowserSubmissionForLocalSimulator() {
        LocalSimulatedMembershipPaymentProviderImpl provider =
                new LocalSimulatedMembershipPaymentProviderImpl(
                        mock(SimulatedPaymentProviderResultStore.class),
                        clock());

        var result = provider.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new BigDecimal("20.00"),
                "alipay",
                "会员模拟支付订单"));

        assertThat(result.providerTradeNo()).isNull();
        assertThat(result.checkoutSubmission()).isNull();
    }

    @Test
    void mapsPaidResultThroughExistingCallbackWithoutInventingChannelTradeNumber() {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-08-21T20:00:00Z");
        SimulatedPaymentProviderResultStore resultStore =
                mock(SimulatedPaymentProviderResultStore.class);
        when(resultStore.find(ORDER_ID)).thenReturn(Optional.of(
                new SimulatedPaymentProviderResult(
                        SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                        ORDER_ID,
                        SimulatedPaymentProviderStatus.PAID,
                        CALLBACK_ID,
                        "local-provider-trade-1",
                        "alipay",
                        new BigDecimal("20.00"),
                        paidAt)));
        LocalSimulatedMembershipPaymentProviderImpl provider =
                new LocalSimulatedMembershipPaymentProviderImpl(resultStore, clock());

        var result = provider.queryPayment(new PaymentQueryCommand(ORDER_ID, null));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(result.providerTradeNo()).isEqualTo("local-provider-trade-1");
        assertThat(result.channelTradeNo()).isNull();
        assertThat(result.finishedAt()).isEqualTo(paidAt);
        assertThat(result.callbackId()).isEqualTo(CALLBACK_ID);
    }

    @Test
    void initializeOrderDoesNotWriteProviderHash() {
        SimulatedPaymentProviderResultStore resultStore =
                mock(SimulatedPaymentProviderResultStore.class);
        LocalSimulatedMembershipPaymentProviderImpl provider =
                new LocalSimulatedMembershipPaymentProviderImpl(resultStore, clock());

        provider.initializeOrder(new com.example.temperate.service.user.membership.payment.provider.PaymentProviderInitializeCommand(
                ORDER_ID, OffsetDateTime.parse("2026-08-21T20:00:00Z")));

        verify(resultStore, never()).initializeUnpaid(anyString(), any());
    }

    @Test
    void firstMissingQueryInitializesUnpaidThenReturnsTheStoredFact() {
        SimulatedPaymentProviderResultStore resultStore =
                mock(SimulatedPaymentProviderResultStore.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-21T20:00:00Z");
        when(resultStore.find(ORDER_ID)).thenReturn(
                Optional.empty(),
                Optional.of(new SimulatedPaymentProviderResult(
                        SimulatedPaymentProviderResult.CURRENT_SCHEMA_VERSION,
                        ORDER_ID,
                        SimulatedPaymentProviderStatus.UNPAID,
                        null,
                        null,
                        null,
                        null,
                        createdAt)));
        LocalSimulatedMembershipPaymentProviderImpl provider =
                new LocalSimulatedMembershipPaymentProviderImpl(resultStore, clock());

        var result = provider.queryPayment(new PaymentQueryCommand(ORDER_ID, null));

        verify(resultStore).initializeUnpaid(ORDER_ID, createdAt);
        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PENDING);
    }

    @Test
    void missingAfterLazyInitializationRemainsUnknown() {
        SimulatedPaymentProviderResultStore resultStore =
                mock(SimulatedPaymentProviderResultStore.class);
        when(resultStore.find(ORDER_ID)).thenReturn(Optional.empty());
        LocalSimulatedMembershipPaymentProviderImpl provider =
                new LocalSimulatedMembershipPaymentProviderImpl(resultStore, clock());

        var result = provider.queryPayment(new PaymentQueryCommand(ORDER_ID, null));

        verify(resultStore).initializeUnpaid(
                ORDER_ID, OffsetDateTime.parse("2026-08-21T20:00:00Z"));
        assertThat(result.status()).isEqualTo(PaymentProviderStatus.UNKNOWN);
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-08-21T20:00:00Z"), ZoneOffset.UTC);
    }
}
