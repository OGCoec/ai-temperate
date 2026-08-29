package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定固定 EDU/TEAM 夹具接口只接受回环请求，并且不允许请求携带用户或套餐参数。
 */
final class MembershipPaymentRestrictedFixtureControllerTest {

    private MembershipPaymentRestrictedFixtureService service;
    private MembershipPaymentRestrictedFixtureController controller;

    @BeforeEach
    void setUp() {
        service = mock(MembershipPaymentRestrictedFixtureService.class);
        controller = new MembershipPaymentRestrictedFixtureController(service);
    }

    @Test
    void loopbackCanPrepareInspectAndRestoreTheFixedFixture() {
        MembershipPaymentRestrictedFixtureState prepared = new MembershipPaymentRestrictedFixtureState(
                true,
                true,
                List.of(new MembershipPaymentRestrictedFixtureUser(84758509811535872L, "EDU")));
        when(service.prepare()).thenReturn(prepared);
        when(service.state()).thenReturn(prepared);
        when(service.restore()).thenReturn(new MembershipPaymentRestrictedFixtureState(
                false, true, prepared.users()));
        MockHttpServletRequest loopback = request("::1");

        ResponseEntity<MembershipPaymentRestrictedFixtureState> prepare = controller.prepare(loopback);
        ResponseEntity<MembershipPaymentRestrictedFixtureState> state = controller.state(loopback);
        ResponseEntity<MembershipPaymentRestrictedFixtureState> restore = controller.restore(loopback);

        assertThat(prepare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prepare.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(state.getBody().prepared()).isTrue();
        assertThat(restore.getBody().prepared()).isFalse();
        verify(service).prepare();
        verify(service).state();
        verify(service).restore();
    }

    @Test
    void nonLoopbackCannotMutateOrInspectFixtures() {
        MockHttpServletRequest remote = request("192.0.2.20");

        assertThatThrownBy(() -> controller.prepare(remote))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.state(remote))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.restore(remote))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
