package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestControlService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定恢复与 Redis 探针入口只接受回环请求，并且 Controller 只委托 Service 接口。
 */
final class MembershipPaymentLoadtestControlControllerTest {

    private MembershipPaymentLoadtestControlService controlService;
    private MembershipPaymentLoadtestControlController controller;

    @BeforeEach
    void setUp() {
        controlService = mock(MembershipPaymentLoadtestControlService.class);
        controller = new MembershipPaymentLoadtestControlController(controlService);
    }

    @Test
    void loopbackCanRecoverCallbackThroughService() {
        var expected = new MembershipPaymentLoadtestControlService.RecoveryProbe(1, 1, 0L);
        when(controlService.recoverOneCallbackProcessing()).thenReturn(expected);

        var response = controller.recoverCallback(request("POST", "127.0.0.1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    @Test
    void loopbackCanInspectOnlyTheRequestedCanonicalOrder() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        var expected = new MembershipPaymentLoadtestControlService.RedisProbe(
                false, false, false, true, 0L, 0L, 0L, 0L);
        when(controlService.inspectOrder(orderId)).thenReturn(expected);

        var response = controller.state(orderId, request("GET", "::1"));

        assertThat(response.getBody()).isEqualTo(expected);
        verify(controlService).inspectOrder(orderId);
    }

    @Test
    void loopbackCanInspectTerminalArtifactsInOneBoundedBatch() {
        String first = "AaAjECcaAQGqi_h2Rl1PiA";
        String second = "AaAjECcaAQGqi_h2Rl1PiB";
        var expected = List.of(
                new MembershipPaymentLoadtestControlService.OrderArtifactProbe(
                        first, false, false),
                new MembershipPaymentLoadtestControlService.OrderArtifactProbe(
                        second, false, false));
        when(controlService.inspectOrderArtifacts(List.of(first, second)))
                .thenReturn(expected);

        var response = controller.stateBatch(
                new MembershipPaymentLoadtestControlController.OrderArtifactRequest(
                        List.of(first, second)),
                request("POST", "127.0.0.1"));

        assertThat(response.getBody()).isEqualTo(expected);
        verify(controlService).inspectOrderArtifacts(List.of(first, second));
    }

    @Test
    void loopbackCanReadQueueBaselineWithoutSupplyingRedisKeys() {
        var expected = new MembershipPaymentLoadtestControlService.RedisQueueProbe(
                0L, 0L, 0L, 0L);
        when(controlService.inspectQueues()).thenReturn(expected);

        var response = controller.queues(request("GET", "::1"));

        assertThat(response.getBody()).isEqualTo(expected);
        verify(controlService).inspectQueues();
    }

    @Test
    void loopbackCanArmAndObserveOneShotCompleteFailure() {
        String orderId = "AaAjECcaAQGqi_h2Rl1PiA";
        var before = new MembershipPaymentLoadtestControlService.FaultProbe(4L);
        var after = new MembershipPaymentLoadtestControlService.FaultProbe(5L);
        when(controlService.armCallbackCompleteFailure(orderId)).thenReturn(before);
        when(controlService.inspectFaults()).thenReturn(after);

        var armed = controller.armCallbackCompleteFailure(
                orderId,
                request("POST", "127.0.0.1"));
        var observed = controller.faults(request("GET", "::1"));

        assertThat(armed.getBody()).isEqualTo(before);
        assertThat(observed.getBody()).isEqualTo(after);
        verify(controlService).armCallbackCompleteFailure(orderId);
        verify(controlService).inspectFaults();
    }

    @Test
    void remoteAddressIsRejectedBeforeAnyControlAction() {
        assertThatThrownBy(() -> controller.flush(request("POST", "192.0.2.10")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(controlService);
    }

    private static MockHttpServletRequest request(String method, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "unused");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
