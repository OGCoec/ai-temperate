package com.example.temperate.service.user.membership.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import org.junit.jupiter.api.Test;

/** 该测试是来固定 provider_trade_no 只能保存外部平台真实交易号，禁止本地订单占位和默认路由回退。 */
class PaymentProviderReferenceTest {

    @Test
    void routesOnlyTaggedExternalTradeReferences() {
        String trade = PaymentProviderReference.trade(
                PaymentProviderType.LIUHAO, "202608301234567890");

        assertThat(trade).isEqualTo("LIUHAO:TRADE:202608301234567890");
        assertThat(PaymentProviderReference.resolveTrade(trade))
                .isEqualTo(PaymentProviderType.LIUHAO);
        assertThat(PaymentProviderReference.rawTradeNo(trade))
                .isEqualTo("202608301234567890");
    }

    @Test
    void neverGuessesAnExternalProviderFromMissingOrUntaggedReferences() {
        assertThat(PaymentProviderReference.tryResolveTrade(null)).isNull();
        assertThat(PaymentProviderReference.tryResolveTrade(" ")).isNull();
        assertThatThrownBy(() -> PaymentProviderReference.resolveTrade("10337931084566528"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentProviderReference.resolveTrade(
                        "LIUHAO:ORDER:AaAjECcaAQGqi_h2Rl1PiA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentProviderReference.rawTradeNo(
                        "LIUHAO:ORDER:AaAjECcaAQGqi_h2Rl1PiA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentProviderReference.rawTradeNo("10337931084566528"))
                .isEqualTo("10337931084566528");
    }

    @Test
    void rejectsLocalSimulatorAndReferencesThatCannotFitExistingColumn() {
        assertThatThrownBy(() -> PaymentProviderReference.trade(
                        PaymentProviderType.LOCAL_SIMULATOR, "trade"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentProviderReference.trade(
                        PaymentProviderType.LIUHAO, "x".repeat(128)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentProviderReference.trade(
                        PaymentProviderType.BAR, "BAR:ORDER:local-order"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
