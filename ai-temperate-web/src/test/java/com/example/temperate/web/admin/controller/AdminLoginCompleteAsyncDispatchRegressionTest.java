package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

/**
 * 通过真实 Spring MVC ASYNC 二次分派锁定管理员登录结果不会被重复风险校验覆盖。
 */
class AdminLoginCompleteAsyncDispatchRegressionTest {

    @Test
    void preservesSuccessfulLoginResultAcrossAsyncDispatch() throws Exception {
        assertPreservedStatus(200);
    }

    @Test
    void preservesInvalidCredentialResultAcrossAsyncDispatch() throws Exception {
        assertPreservedStatus(401);
    }

    @Test
    void preservesHumanVerificationTimeoutAcrossAsyncDispatch() throws Exception {
        assertPreservedStatus(503);
    }

    private static void assertPreservedStatus(int expectedStatus) throws Exception {
        Fixture fixture = fixture();

        MvcResult pending = fixture.mockMvc()
                .perform(post("/api/admin/auth/login/complete")
                        .param("resultStatus", Integer.toString(expectedStatus))
                        .header("X-Client-Platform", "H5")
                        .header("X-Device-Installation-Id", "test-device"))
                .andExpect(request().asyncStarted())
                .andReturn();

        fixture.mockMvc()
                .perform(asyncDispatch(pending))
                .andExpect(status().is(expectedStatus));

        assertThat(fixture.controller().completionCount()).isEqualTo(1);
        verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
        verify(fixture.assessmentService(), times(1))
                .assess(fixture.access(), fixture.observation());
        verify(fixture.webRtcService(), times(1))
                .inspect(fixture.access(), fixture.observation().clientIp());
        verify(fixture.preAuthService(), times(expectedStatus == 200 ? 1 : 0))
                .promoteAuthenticated(
                        fixture.access(),
                        RiskSessionType.ADMIN_SESSION,
                        "test-admin-session-token",
                        fixture.observation().observedAt());
    }

    private static Fixture fixture() {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(15),
                List.of(URI.create("stun:stun.cloudflare.com:3478")),
                8,
                ""));

        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(any(), any(), any()))
                .thenReturn(Optional.of(access));

        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-26T16:06:24Z"));
        when(preAuthService.promoteAuthenticated(
                        access,
                        RiskSessionType.ADMIN_SESSION,
                        "test-admin-session-token",
                        observation.observedAt()))
                .thenReturn(new PreAuthIssue(
                        "test-promoted-preauth-token",
                        observation.observedAt().plus(Duration.ofHours(6))));
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation));

        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        when(assessmentService.assess(access, observation))
                .thenReturn(
                        Mono.just(new RiskAssessment(
                                RiskDecision.ALLOW,
                                100,
                                false,
                                0,
                                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                                HmacIdentifier.fromProtectedValue("C".repeat(43)))),
                        Mono.error(new PreAuthRequiredException()));
        NetworkRiskInterceptor networkRiskInterceptor = new NetworkRiskInterceptor(
                properties,
                preAuthService,
                assessmentService,
                mock(RiskChallengeService.class),
                contextResolver,
                mock(PreAuthTransport.class),
                new ObjectMapper(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));

        WebRtcVerificationService webRtcService = mock(WebRtcVerificationService.class);
        when(webRtcService.inspect(access, observation.clientIp()))
                .thenReturn(
                        WebRtcVerificationDecision.verified(
                                List.of(observation.clientIp())),
                        WebRtcVerificationDecision.required());
        WebRtcVerificationInterceptor webRtcInterceptor =
                new WebRtcVerificationInterceptor(
                        properties,
                        webRtcService,
                        contextResolver,
                        new ObjectMapper(),
                        new WebRtcMetrics(new SimpleMeterRegistry()));

        AsyncLoginCompleteController controller = new AsyncLoginCompleteController(
                preAuthService,
                access,
                observation);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .addInterceptors(networkRiskInterceptor, webRtcInterceptor)
                .build();
        return new Fixture(
                mockMvc,
                controller,
                preAuthService,
                assessmentService,
                webRtcService,
                access,
                observation);
    }

    private record Fixture(
            MockMvc mockMvc,
            AsyncLoginCompleteController controller,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            WebRtcVerificationService webRtcService,
            PreAuthAccess access,
            TrustedNetworkObservation observation) {
    }

    /**
     * 模拟生产登录完成接口的 Mono 结果，只建模异步分派和最终状态，不连接 hCaptcha 或外部服务。
     */
    @Controller
    public static final class AsyncLoginCompleteController {

        private final AtomicInteger completionCount = new AtomicInteger();
        private final PreAuthService preAuthService;
        private final PreAuthAccess access;
        private final TrustedNetworkObservation observation;

        private AsyncLoginCompleteController(
                PreAuthService preAuthService,
                PreAuthAccess access,
                TrustedNetworkObservation observation) {
            this.preAuthService = preAuthService;
            this.access = access;
            this.observation = observation;
        }

        @PostMapping("/api/admin/auth/login/complete")
        public Mono<ResponseEntity<Void>> complete(
                @RequestParam("resultStatus") int resultStatus) {
            return Mono.fromSupplier(() -> {
                completionCount.incrementAndGet();
                if (resultStatus == 200) {
                    preAuthService.promoteAuthenticated(
                            access,
                            RiskSessionType.ADMIN_SESSION,
                            "test-admin-session-token",
                            observation.observedAt());
                }
                return ResponseEntity.status(resultStatus).build();
            });
        }

        private int completionCount() {
            return completionCount.get();
        }
    }
}
