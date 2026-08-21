package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentLoadtestFaultGateImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 callback complete 故障只能在压测开关开启时武装、只命中目标订单一次并留下单调计数。
 */
final class MembershipPaymentLoadtestFaultGateImplTest {

    private static final String FIRST_ORDER_ID = "AaAjECcaAQGqi_h2Rl1PiA";
    private static final String SECOND_ORDER_ID = "AaAjECcaAQGqi_h2Rl1Piw";

    @Test
    void armedFaultSkipsAnotherOrderAndFailsTargetOnlyOnce() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(17L)));
        assertThat(gate.armCallbackCompleteFailure(FIRST_ORDER_ID)).isZero();

        gate.failBeforeCallbackCompleteIfArmed(List.of(completion(SECOND_ORDER_ID)));
        assertThat(gate.callbackCompleteFailureCount()).isZero();
        assertThatThrownBy(() -> gate.failBeforeCallbackCompleteIfArmed(
                        List.of(completion(FIRST_ORDER_ID))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Injected loadtest");
        assertThat(gate.callbackCompleteFailureCount()).isEqualTo(1L);

        gate.failBeforeCallbackCompleteIfArmed(List.of(completion(FIRST_ORDER_ID)));
        assertThat(gate.callbackCompleteFailureCount()).isEqualTo(1L);
    }

    @Test
    void disabledLoadtestCannotArmFault() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(false, List.of()));

        assertThatThrownBy(() -> gate.armCallbackCompleteFailure(FIRST_ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    private static PaymentCallbackCompletion completion(String orderId) {
        return new PaymentCallbackCompletion(
                new PaymentCallbackClaim("AaAjECcaAQGqi_h2Rl1PiQ", 1L),
                orderId);
    }
}
