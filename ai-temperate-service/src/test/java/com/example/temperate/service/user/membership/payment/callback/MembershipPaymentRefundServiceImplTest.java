package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentRefundServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来固定退款尝试对成功、明确失败和受控网络超时的类型化分类，防止异常正文参与重试裁决。
 */
class MembershipPaymentRefundServiceImplTest {

    private static final PaymentRefundCommand COMMAND = new PaymentRefundCommand(
            "AAAAAAAAAAAAAAABAAAAAA",
            "LIUHAO:TRADE:trade-reference",
            new BigDecimal("0.20"));

    private MembershipPaymentProvider provider;
    private MembershipPaymentMetrics metrics;
    private MembershipPaymentRefundService service;

    @BeforeEach
    void setUp() {
        provider = mock(MembershipPaymentProvider.class);
        when(provider.type()).thenReturn(PaymentProviderType.LIUHAO);
        MembershipPaymentProviderRegistry registry = mock(MembershipPaymentProviderRegistry.class);
        when(registry.getRequired(PaymentProviderType.LIUHAO)).thenReturn(provider);
        metrics = mock(MembershipPaymentMetrics.class);
        service = new MembershipPaymentRefundServiceImpl(
                registry,
                mock(MembershipPaymentProperties.class),
                metrics);
    }

    @Test
    void returnsSucceededOnlyForMatchingFullRefund() {
        when(provider.refundPayment(COMMAND)).thenReturn(new PaymentRefundResult(
                PaymentProviderStatus.REFUNDED,
                COMMAND.providerTradeNo(),
                "provider-refund-reference",
                COMMAND.amountYuan()));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 1);

        assertThat(result).isEqualTo(new PaymentRefundAttemptResult(
                PaymentRefundAttemptOutcome.SUCCEEDED,
                "VERIFIED",
                PaymentProviderType.LIUHAO,
                1));
        verify(metrics).refundRequired();
    }

    @Test
    void returnsTimedOutOnlyForControlledTimeoutCodeAndCause() {
        when(provider.refundPayment(COMMAND)).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_TIMEOUT,
                "arbitrary text that is never parsed",
                new SocketTimeoutException("sensitive timeout detail")));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 2);

        assertThat(result.outcome()).isEqualTo(PaymentRefundAttemptOutcome.TIMED_OUT);
        assertThat(result.safeReason()).isEqualTo("LIUHAO_TIMEOUT");
        assertThat(result.attemptNo()).isEqualTo(2);
        verifyNoInteractions(metrics);
    }

    @Test
    void timeoutCodeWithoutControlledTimeoutCauseIsExplicitFailure() {
        when(provider.refundPayment(COMMAND)).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_TIMEOUT,
                "timeout text without a controlled timeout cause"));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 2);

        assertThat(result.outcome())
                .isEqualTo(PaymentRefundAttemptOutcome.EXPLICIT_FAILURE);
        assertThat(result.safeReason()).isEqualTo("TIMEOUT_CAUSE_UNVERIFIED");
        verifyNoInteractions(metrics);
    }

    @Test
    void classifiesUnsignedProviderResponseAsExplicitFailure() {
        when(provider.refundPayment(COMMAND)).thenThrow(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                "provider response omitted signature metadata"));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 1);

        assertThat(result.outcome())
                .isEqualTo(PaymentRefundAttemptOutcome.EXPLICIT_FAILURE);
        assertThat(result.safeReason()).isEqualTo("LIUHAO_SIGNATURE_INVALID");
        verifyNoInteractions(metrics);
    }

    @Test
    void classifiesUnexpectedRuntimeFailureAsExplicitFailure() {
        when(provider.refundPayment(COMMAND))
                .thenThrow(new IllegalStateException("contains timeout but is not a timeout type"));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 1);

        assertThat(result.outcome())
                .isEqualTo(PaymentRefundAttemptOutcome.EXPLICIT_FAILURE);
        assertThat(result.safeReason()).isEqualTo("UNCLASSIFIED_NON_TIMEOUT_FAILURE");
        verifyNoInteractions(metrics);
    }

    @Test
    void classifiesMismatchedRefundConfirmationAsExplicitFailure() {
        when(provider.refundPayment(COMMAND)).thenReturn(new PaymentRefundResult(
                PaymentProviderStatus.REFUNDED,
                COMMAND.providerTradeNo(),
                "provider-refund-reference",
                new BigDecimal("0.19")));

        PaymentRefundAttemptResult result = service.refund(COMMAND, 1);

        assertThat(result.outcome())
                .isEqualTo(PaymentRefundAttemptOutcome.EXPLICIT_FAILURE);
        assertThat(result.safeReason()).isEqualTo("PROVIDER_REFUND_NOT_CONFIRMED");
        verifyNoInteractions(metrics);
    }
}
