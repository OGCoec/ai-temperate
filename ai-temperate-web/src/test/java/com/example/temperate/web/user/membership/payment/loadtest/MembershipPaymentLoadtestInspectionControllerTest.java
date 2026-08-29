package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestControlService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定共享 BAR 浸泡只能通过回环只读入口检查 Redis 队列与终态订单工件，不能获得故障注入能力。
 */
final class MembershipPaymentLoadtestInspectionControllerTest {

    @Test
    void exposesOnlyTheTwoReadOnlyInspectionOperationsWithNoStore() {
        MembershipPaymentLoadtestControlService service =
                mock(MembershipPaymentLoadtestControlService.class);
        var queues = new MembershipPaymentLoadtestControlService.RedisQueueProbe(
                0L, 0L, 0L, 0L);
        var artifacts = List.of(
                new MembershipPaymentLoadtestControlService.OrderArtifactProbe(
                        "AaAjECcaAQGqi_h2Rl1PiA", false, false));
        when(service.inspectQueues()).thenReturn(queues);
        when(service.inspectOrderArtifacts(List.of("AaAjECcaAQGqi_h2Rl1PiA")))
                .thenReturn(artifacts);
        MembershipPaymentLoadtestInspectionController controller =
                new MembershipPaymentLoadtestInspectionController(service);
        MockHttpServletRequest request = loopbackRequest();

        var queueResponse = controller.queues(request);
        var artifactResponse = controller.stateBatch(
                new MembershipPaymentLoadtestInspectionController.OrderArtifactRequest(
                        List.of("AaAjECcaAQGqi_h2Rl1PiA")),
                request);

        assertThat(queueResponse.getBody()).isEqualTo(queues);
        assertThat(artifactResponse.getBody()).isEqualTo(artifacts);
        assertThat(queueResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(artifactResponse.getHeaders().getCacheControl()).contains("no-store");
        verify(service).inspectQueues();
        verify(service).inspectOrderArtifacts(List.of("AaAjECcaAQGqi_h2Rl1PiA"));
    }

    @Test
    void rejectsNonLoopbackRequestsBeforeReadingState() {
        MembershipPaymentLoadtestInspectionController controller =
                new MembershipPaymentLoadtestInspectionController(
                        mock(MembershipPaymentLoadtestControlService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");

        assertThatThrownBy(() -> controller.queues(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void isRegisteredOnlyForRealtimeAndSharedBarLoadtestProfiles() {
        Profile profile = MembershipPaymentLoadtestInspectionController.class
                .getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder(
                "loadtest-realtime", "loadtest-bar");
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
