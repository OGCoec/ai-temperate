package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定十六账号 FREE 基线接口只接受回环请求，并且不接受客户端选择用户或等级。
 */
final class MembershipPaymentBaselineFixtureControllerTest {

    private MembershipPaymentBaselineFixtureService service;
    private MembershipPaymentBaselineFixtureController controller;

    @BeforeEach
    void setUp() {
        service = mock(MembershipPaymentBaselineFixtureService.class);
        controller = new MembershipPaymentBaselineFixtureController(service);
    }

    @Test
    void loopbackCanPrepareAndInspectTheFixedBaseline() {
        MembershipPaymentBaselineFixtureState state = new MembershipPaymentBaselineFixtureState(
                true,
                List.of(new MembershipPaymentBaselineFixtureUser(
                        72659006262480896L, "FREE")));
        when(service.prepare()).thenReturn(state);
        when(service.state()).thenReturn(state);
        MockHttpServletRequest loopback = request("127.0.0.1");

        ResponseEntity<MembershipPaymentBaselineFixtureState> prepare =
                controller.prepare(loopback);
        ResponseEntity<MembershipPaymentBaselineFixtureState> inspect =
                controller.state(loopback);

        assertThat(prepare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prepare.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(inspect.getBody().prepared()).isTrue();
        verify(service).prepare();
        verify(service).state();
    }

    @Test
    void nonLoopbackCannotMutateOrInspectTheBaseline() {
        MockHttpServletRequest remote = request("192.0.2.20");

        assertThatThrownBy(() -> controller.prepare(remote))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.state(remote))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
