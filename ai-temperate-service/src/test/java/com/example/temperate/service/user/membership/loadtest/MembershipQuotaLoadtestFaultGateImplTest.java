package com.example.temperate.service.user.membership.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.loadtest.impl.MembershipQuotaLoadtestFaultGateImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定额度事务故障只能命中白名单目标一次，其他用户和普通 Profile 不能武装或被误伤。
 */
final class MembershipQuotaLoadtestFaultGateImplTest {

    private static final long USER_ID = 84755204414771200L;

    @Test
    void armedFailureHitsOnlyTargetOnceAndIncrementsEvidence() {
        MembershipQuotaLoadtestFaultGate gate = enabledGate();

        assertThat(gate.armReservationRollback(USER_ID)).isZero();
        gate.failAfterReservationIfArmed(USER_ID + 1L);
        assertThat(gate.reservationRollbackArmed()).isTrue();

        assertThatThrownBy(() -> gate.failAfterReservationIfArmed(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollback");
        assertThat(gate.reservationRollbackArmed()).isFalse();
        assertThat(gate.reservationRollbackFailureCount()).isEqualTo(1L);

        gate.failAfterReservationIfArmed(USER_ID);
        assertThat(gate.reservationRollbackFailureCount()).isEqualTo(1L);
    }

    @Test
    void rejectsDisabledAndNonAllowlistedArming() {
        MembershipQuotaLoadtestFaultGate disabled =
                new MembershipQuotaLoadtestFaultGateImpl(
                        new MembershipPaymentLoadtestProperties(false, List.of()));
        assertThatThrownBy(() -> disabled.armReservationRollback(USER_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> enabledGate().armReservationRollback(USER_ID + 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void refusesASecondConcurrentArm() {
        MembershipQuotaLoadtestFaultGate gate = enabledGate();
        gate.armReservationRollback(USER_ID);

        assertThatThrownBy(() -> gate.armReservationRollback(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already armed");
    }

    private static MembershipQuotaLoadtestFaultGate enabledGate() {
        return new MembershipQuotaLoadtestFaultGateImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(USER_ID)));
    }
}
