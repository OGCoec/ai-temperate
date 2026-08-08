package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证网络风险观察模式只记录迁移缺口，而强制模式才阻断缺少 PreAuth 的请求。
 */
class NetworkRiskInterceptorModeTest {

    @Test
    void observeModeAllowsLegacyRequestWithoutPreAuth() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.OBSERVE);

        boolean allowed = fixture.interceptor().preHandle(
                request(),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        verify(fixture.assessmentService(), never())
                .assess(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enforceModeReturns428WhenPreAuthIsMissing() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(
                request(),
                response,
                new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("PREAUTH_REQUIRED");
    }

    @Test
    void androidChallengeReturnsControlledUnavailableWithoutIssuingWafReference()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.preAuthService().resolve(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(access));
        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-25T12:00:00Z"));
        when(fixture.contextResolver().resolve(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(observation));
        when(fixture.properties().lookupTimeout())
                .thenReturn(Duration.ofSeconds(8));
        when(fixture.assessmentService().assess(access, observation))
                .thenReturn(Mono.just(new RiskAssessment(
                        RiskDecision.CHALLENGE,
                        40,
                        false,
                        0,
                        HmacIdentifier.fromProtectedValue(
                                "A".repeat(43)),
                        HmacIdentifier.fromProtectedValue(
                                "B".repeat(43)))));
        MockHttpServletRequest request = request();
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(
                request,
                response,
                new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("RISK_CHALLENGE_UNAVAILABLE");
        verify(fixture.challengeService(), never())
                .issue(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ipChangeRefreshesRequestPreAuthBeforeDownstreamTokenRotation()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        HmacIdentifier oldIp = HmacIdentifier.fromProtectedValue("A".repeat(43));
        HmacIdentifier newIp = HmacIdentifier.fromProtectedValue("B".repeat(43));
        PreAuthState oldState = mock(PreAuthState.class);
        when(oldState.currentIpDigest()).thenReturn(oldIp);
        PreAuthAccess oldAccess = mock(PreAuthAccess.class);
        when(oldAccess.state()).thenReturn(oldState);
        PreAuthAccess refreshedAccess = mock(PreAuthAccess.class);
        when(fixture.preAuthService().resolve(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(oldAccess), Optional.of(refreshedAccess));
        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-25T12:00:00Z"));
        when(fixture.contextResolver().resolve(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(observation));
        when(fixture.properties().lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        when(fixture.assessmentService().assess(oldAccess, observation))
                .thenReturn(Mono.just(new RiskAssessment(
                        RiskDecision.ALLOW,
                        80,
                        false,
                        0,
                        newIp,
                        HmacIdentifier.fromProtectedValue("C".repeat(43)))));
        MockHttpServletRequest request = request();

        boolean allowed = fixture.interceptor().preHandle(
                request,
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE))
                .isSameAs(refreshedAccess);
        verify(fixture.preAuthService(), times(2)).resolve(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture(NetworkRiskMode mode) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(mode);
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        RiskChallengeService challengeService =
                mock(RiskChallengeService.class);
        RiskRequestContextResolver contextResolver =
                mock(RiskRequestContextResolver.class);
        NetworkRiskInterceptor interceptor = new NetworkRiskInterceptor(
                properties,
                preAuthService,
                assessmentService,
                challengeService,
                contextResolver,
                mock(PreAuthTransport.class),
                new ObjectMapper(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
        return new Fixture(
                interceptor,
                properties,
                preAuthService,
                assessmentService,
                challengeService,
                contextResolver);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/login/password");
        request.addHeader("X-Device-Installation-Id", "test-device-0001");
        return request;
    }

    private record Fixture(
            NetworkRiskInterceptor interceptor,
            NetworkRiskProperties properties,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            RiskChallengeService challengeService,
            RiskRequestContextResolver contextResolver) {
    }
}
