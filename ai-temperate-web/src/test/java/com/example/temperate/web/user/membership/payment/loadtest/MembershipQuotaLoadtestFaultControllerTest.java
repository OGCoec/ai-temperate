package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.loadtest.MembershipQuotaLoadtestFaultGate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定额度回滚控制器只接受回环请求，并只返回武装状态和单调触发次数。
 */
final class MembershipQuotaLoadtestFaultControllerTest {

    @Test
    void armsAllowlistedUserAndReturnsLowCardinalityEvidence() {
        MembershipQuotaLoadtestFaultGate gate =
                mock(MembershipQuotaLoadtestFaultGate.class);
        when(gate.armReservationRollback(84755204414771200L)).thenReturn(3L);
        MembershipQuotaLoadtestFaultController controller =
                new MembershipQuotaLoadtestFaultController(gate);

        var response = controller.arm(
                84755204414771200L, loopbackRequest());

        assertThat(response.getBody().armed()).isTrue();
        assertThat(response.getBody().failureCount()).isEqualTo(3L);
        verify(gate).armReservationRollback(84755204414771200L);
    }

    @Test
    void readsStateAndRejectsRemoteSource() {
        MembershipQuotaLoadtestFaultGate gate =
                mock(MembershipQuotaLoadtestFaultGate.class);
        when(gate.reservationRollbackArmed()).thenReturn(false);
        when(gate.reservationRollbackFailureCount()).thenReturn(4L);
        MembershipQuotaLoadtestFaultController controller =
                new MembershipQuotaLoadtestFaultController(gate);

        assertThat(controller.state(loopbackRequest()).getBody().failureCount())
                .isEqualTo(4L);
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.40");
        assertThatThrownBy(() -> controller.state(remote))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void isRegisteredOnlyForBarLoadtestProfile() {
        Profile profile = MembershipQuotaLoadtestFaultController.class
                .getAnnotation(Profile.class);
        assertThat(profile.value()).containsExactly("loadtest-bar");
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
