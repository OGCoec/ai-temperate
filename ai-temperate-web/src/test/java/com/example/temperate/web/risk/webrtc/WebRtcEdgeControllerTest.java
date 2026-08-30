package com.example.temperate.web.risk.webrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTiming;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证 WebRTC Start/Report 的固定协议、状态码、失败详情与禁止缓存响应。
 */
class WebRtcEdgeControllerTest {

    @Test
    void startActuallyBeginsGenerationAndReturnsRedisRemainingWindow() {
        Fixture fixture = fixture();
        when(fixture.service().begin(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.pending(
                        12L,
                        Instant.now().plusSeconds(15),
                        15_000L));

        var response = fixture.controller().startUser(
                "device-installation-0001",
                "H5",
                request("GET", "/api/_edge/webrtc/start"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().probeRequired()).isTrue();
        assertThat(response.getBody().verificationState()).isEqualTo("PENDING");
        assertThat(response.getBody().probeGeneration()).isEqualTo("12");
        assertThat(response.getBody().pendingRemainingMillis()).isEqualTo(15_000L);
        assertThat(response.getBody().timeoutMillis()).isEqualTo(12_000L);
        assertThat(response.getBody().reportGraceMillis()).isEqualTo(3_000L);
        assertThat(response.getBody().stunUrls()).containsExactly(
                "stun:stun.l.google.com:19302",
                "stun:stun.cloudflare.com:3478",
                "stun:global.stun.twilio.com:3478",
                "stun:stun.nextcloud.com:3478");
    }

    @Test
    void reportReturns403AndBothIpViewsForMismatch() {
        Fixture fixture = fixture();
        when(fixture.service().report(
                        any(),
                        eq("8.8.8.8"),
                        eq("12"),
                        eq(List.of("1.1.1.1"))))
                .thenReturn(WebRtcVerificationDecision.failed(
                        12L,
                        Instant.now().plusSeconds(20),
                        com.example.temperate.service.risk.preauth.domain
                                .PreAuthWebRtcFailureReason.IP_MISMATCH,
                        List.of("1.1.1.1")));

        var response = fixture.controller().reportUser(
                "device-installation-0001",
                "H5",
                new WebRtcEdgeController.WebRtcReportRequest(
                        "12",
                        List.of("1.1.1.1")),
                request("POST", "/api/_edge/webrtc/report"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("WEBRTC_IP_MISMATCH");
        assertThat(response.getBody().verificationState()).isEqualTo("FAILED");
        assertThat(response.getBody().httpIp()).isEqualTo("8.8.8.8");
        assertThat(response.getBody().webRtcIps()).containsExactly("1.1.1.1");
    }

    @Test
    void reportReturns428AndCompleteEvidenceWhenHttpFamilyIsMissing() {
        Fixture fixture = fixture();
        when(fixture.service().report(
                        any(),
                        eq("8.8.8.8"),
                        eq("13"),
                        eq(List.of("2606:4700:4700::1111"))))
                .thenReturn(WebRtcVerificationDecision.failed(
                        13L,
                        com.example.temperate.service.risk.preauth.domain
                                .PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE,
                        List.of("2606:4700:4700::1111")));

        MockHttpServletRequest request = request("POST", "/api/_edge/webrtc/report");
        AuthRequestTiming.initialize(request, true);
        var response = fixture.controller().reportUser(
                "device-installation-0001",
                "H5",
                new WebRtcEdgeController.WebRtcReportRequest(
                        "13",
                        List.of("2606:4700:4700::1111")),
                request);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("WEBRTC_IP_FAMILY_INCOMPLETE");
        assertThat(response.getBody().verificationState()).isEqualTo("FAILED");
        assertThat(response.getBody().webRtcStatus()).isFalse();
        assertThat(response.getBody().retryable()).isFalse();
        assertThat(response.getBody().httpIp()).isEqualTo("8.8.8.8");
        assertThat(response.getBody().webRtcIps())
                .containsExactly("2606:4700:4700::1111");
        assertThat(AuthRequestTiming.errorCode(request))
                .isEqualTo("WEBRTC_IP_FAMILY_INCOMPLETE");
    }

    @Test
    void sourceContainsNoBackendStunClientOrEmbeddedFrontend() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/temperate/web/risk/webrtc/"
                        + "WebRtcEdgeController.java"));

        assertThat(source)
                .doesNotContain("WebClient", "Mono<", "Flux<", "<html", "<script", "<style")
                .contains("/api/_edge/webrtc/start", "/api/admin/_edge/webrtc/report");
    }

    private static Fixture fixture() {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                ""));
        AuthSecurityProperties auth = mock(AuthSecurityProperties.class);
        AuthSecurityProperties.Cors cors = mock(AuthSecurityProperties.Cors.class);
        when(auth.cors()).thenReturn(cors);
        when(cors.allowedOrigins()).thenReturn(List.of("https://shop.example.test"));
        AdminProperties admin = mock(AdminProperties.class);
        when(admin.allowedOrigins()).thenReturn(List.of("https://admin.example.test"));
        WebRtcVerificationService service = mock(WebRtcVerificationService.class);
        RiskRequestContextResolver resolver = mock(RiskRequestContextResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new TrustedNetworkObservation(
                        "8.8.8.8",
                        "US",
                        15169L,
                        null,
                        null,
                        Instant.parse("2026-07-25T12:00:00Z"))));
        WebRtcEdgeController controller = new WebRtcEdgeController(
                properties,
                auth,
                admin,
                service,
                resolver,
                new WebRtcMetrics(new SimpleMeterRegistry()));
        return new Fixture(controller, service);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Origin", "https://shop.example.test");
        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthState state = mock(PreAuthState.class);
        when(access.state()).thenReturn(state);
        when(state.webRtcPhase()).thenReturn(path.endsWith("/report")
                ? PreAuthWebRtcPhase.PENDING
                : PreAuthWebRtcPhase.REQUIRED);
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                access);
        return request;
    }

    private record Fixture(
            WebRtcEdgeController controller,
            WebRtcVerificationService service) {
    }
}
