package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackClaim;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackCompletion;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentLoadtestFaultGateImpl;
import java.time.Duration;
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

    @Test
    void callbackHoldOnlyMatchesTargetAndReleaseIsIdempotent() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(17L)));

        gate.armCallbackHold(FIRST_ORDER_ID, Duration.ofSeconds(60));

        assertThat(gate.callbackHeld(FIRST_ORDER_ID)).isTrue();
        assertThat(gate.callbackHeld(SECOND_ORDER_ID)).isFalse();
        assertThat(gate.callbackHoldRemainingMillis(FIRST_ORDER_ID)).isPositive();

        gate.releaseCallbackHold(FIRST_ORDER_ID);
        gate.releaseCallbackHold(FIRST_ORDER_ID);

        assertThat(gate.callbackHeld(FIRST_ORDER_ID)).isFalse();
        assertThat(gate.callbackHoldRemainingMillis(FIRST_ORDER_ID)).isZero();
    }

    @Test
    void callbackHoldsCanCoverMultipleFixedOrdersConcurrently() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(17L)));

        gate.armCallbackHold(FIRST_ORDER_ID, Duration.ofSeconds(60));
        gate.armCallbackHold(SECOND_ORDER_ID, Duration.ofSeconds(60));

        assertThat(gate.callbackHeld(FIRST_ORDER_ID)).isTrue();
        assertThat(gate.callbackHeld(SECOND_ORDER_ID)).isTrue();

        gate.releaseCallbackHold(FIRST_ORDER_ID);

        assertThat(gate.callbackHeld(FIRST_ORDER_ID)).isFalse();
        assertThat(gate.callbackHeld(SECOND_ORDER_ID)).isTrue();
    }

    @Test
    void workerPauseCanBeObservedAndExplicitlyReleased() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(17L)));

        gate.pauseCallbackWorker(Duration.ofSeconds(60));
        gate.pauseOrderPersistenceWorker(Duration.ofSeconds(60));

        assertThat(gate.callbackWorkerPaused()).isTrue();
        assertThat(gate.orderPersistenceWorkerPaused()).isTrue();
        assertThat(gate.callbackWorkerPauseRemainingMillis()).isPositive();
        assertThat(gate.orderPersistenceWorkerPauseRemainingMillis()).isPositive();

        gate.resumeCallbackWorker();
        gate.resumeOrderPersistenceWorker();

        assertThat(gate.callbackWorkerPaused()).isFalse();
        assertThat(gate.orderPersistenceWorkerPaused()).isFalse();
    }

    @Test
    void holdAndPauseRejectDurationsOutsideBoundedWindow() {
        MembershipPaymentLoadtestFaultGate gate = new MembershipPaymentLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(17L)));

        assertThatThrownBy(() -> gate.armCallbackHold(
                        FIRST_ORDER_ID, Duration.ofSeconds(181)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("180");
        assertThatThrownBy(() -> gate.pauseCallbackWorker(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 180 seconds");
    }

    private static PaymentCallbackCompletion completion(String orderId) {
        return new PaymentCallbackCompletion(
                new PaymentCallbackClaim("AaAjECcaAQGqi_h2Rl1PiQ", 1L),
                orderId);
    }
}
