package com.example.temperate.service.user.membership.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来固定支付提供方注册表的类型选择、重复注册和未知类型失败合同。
 */
class MembershipPaymentProviderRegistryTest {

    @Test
    void selectsProviderByStableEnumType() {
        MembershipPaymentProvider local = new StubProvider(PaymentProviderType.LOCAL_SIMULATOR);
        MembershipPaymentProvider bar = new StubProvider(PaymentProviderType.BAR);
        MembershipPaymentProvider liuhao = new StubProvider(PaymentProviderType.LIUHAO);
        MembershipPaymentProviderRegistry registry =
                new MembershipPaymentProviderRegistry(Map.of(
                        "local", local, "bar", bar, "liuhao", liuhao));

        assertThat(registry.getRequired(PaymentProviderType.LOCAL_SIMULATOR)).isSameAs(local);
        assertThat(registry.getRequired(PaymentProviderType.BAR)).isSameAs(bar);
        assertThat(registry.getRequired(PaymentProviderType.LIUHAO)).isSameAs(liuhao);
    }

    @Test
    void rejectsDuplicateProviderTypesAtStartup() {
        assertThatThrownBy(() -> new MembershipPaymentProviderRegistry(Map.of(
                        "barOne", new StubProvider(PaymentProviderType.BAR),
                        "barTwo", new StubProvider(PaymentProviderType.BAR))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BAR");
    }

    @Test
    void rejectsUnknownProviderType() {
        MembershipPaymentProviderRegistry registry =
                new MembershipPaymentProviderRegistry(Map.of());

        assertThatThrownBy(() -> registry.getRequired(PaymentProviderType.BAR))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.PAYMENT_PROVIDER_UNSUPPORTED));
    }

    @Test
    void rejectsMissingConfiguredDefaultProviderAtStartup() {
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.defaultProvider()).thenReturn(PaymentProviderType.BAR);

        assertThatThrownBy(() -> new MembershipPaymentProviderRegistry(
                        Map.of(
                                "local",
                                new StubProvider(PaymentProviderType.LOCAL_SIMULATOR)),
                        properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BAR");
    }

    private record StubProvider(PaymentProviderType type)
            implements MembershipPaymentProvider {

        @Override
        public void initializeOrder(PaymentProviderInitializeCommand command) {
        }

        @Override
        public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentCreateResult createPayment(PaymentCreateCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentCloseResult closePayment(PaymentCloseCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
            throw new UnsupportedOperationException();
        }
    }
}
