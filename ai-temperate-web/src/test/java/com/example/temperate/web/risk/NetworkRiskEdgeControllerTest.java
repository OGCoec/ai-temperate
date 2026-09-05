package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthBootstrapOutcome;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.service.PreAuthRiskBootstrapService;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证 H5 与 Android 风险挑战合同、完成跳转和基础设施故障分类。
 */
class NetworkRiskEdgeControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-10T18:00:00Z");
    private static final String PREAUTH_TOKEN = "A".repeat(43);
    private static final String CHALLENGE_REF = "B".repeat(43);

    @Test
    void androidBootstrapReturnsTokenWhileH5KeepsItOutOfJson() {
        Fixture fixture = fixture();
        PreAuthAccess access = mock(PreAuthAccess.class);
        RiskAssessment assessment = new RiskAssessment(
                RiskDecision.CHALLENGE,
                40,
                false,
                0L,
                HmacIdentifier.fromProtectedValue("C".repeat(43)),
                HmacIdentifier.fromProtectedValue("D".repeat(43)));
        PreAuthBootstrapOutcome outcome = new PreAuthBootstrapOutcome(
                new PreAuthIssue(PREAUTH_TOKEN, NOW.plus(Duration.ofMinutes(30))),
                access,
                assessment,
                new RiskChallengeIssue(
                        CHALLENGE_REF,
                        NOW.plus(Duration.ofMinutes(3))),
                false);
        when(fixture.bootstrapService().bootstrap(
                        eq(RiskScope.USER),
                        any(),
                        eq("device-test"),
                        any(),
                        eq(false)))
                .thenReturn(Mono.just(outcome));

        ResponseEntity<NetworkRiskEdgeController.BootstrapResponse> android =
                fixture.controller().bootstrapUser(
                        "device-test",
                        "ANDROID",
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse());
        ResponseEntity<NetworkRiskEdgeController.BootstrapResponse> h5 =
                fixture.controller().bootstrapUser(
                        "device-test",
                        "H5",
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse());

        assertThat(android.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(android.getBody()).isNotNull();
        assertThat(android.getBody().preAuthToken()).isEqualTo(PREAUTH_TOKEN);
        assertThat(android.getBody().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        assertThat(h5.getBody()).isNotNull();
        assertThat(h5.getBody().preAuthToken()).isNull();
    }

    @Test
    void wechatMiniProgramBootstrapReturnsTokenWithoutCookies() {
        Fixture fixture = fixture();
        PreAuthAccess access = mock(PreAuthAccess.class);
        RiskAssessment assessment = new RiskAssessment(
                RiskDecision.ALLOW,
                0,
                false,
                0L,
                HmacIdentifier.fromProtectedValue("C".repeat(43)),
                HmacIdentifier.fromProtectedValue("D".repeat(43)));
        PreAuthBootstrapOutcome outcome = new PreAuthBootstrapOutcome(
                new PreAuthIssue(PREAUTH_TOKEN, NOW.plus(Duration.ofMinutes(30))),
                access,
                assessment,
                null,
                false);
        when(fixture.bootstrapService().bootstrap(
                        eq(RiskScope.USER),
                        any(),
                        eq("device-wechat"),
                        any(),
                        eq(false)))
                .thenReturn(Mono.just(outcome));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ResponseEntity<NetworkRiskEdgeController.BootstrapResponse> wechat =
                fixture.controller().bootstrapUser(
                        "device-wechat",
                        "WECHAT_MINI_PROGRAM",
                        new MockHttpServletRequest(),
                        servletResponse);

        assertThat(wechat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wechat.getBody()).isNotNull();
        assertThat(wechat.getBody().preAuthToken()).isEqualTo(PREAUTH_TOKEN);
        assertThat(servletResponse.getHeaderNames()).doesNotContain("Set-Cookie");
    }

    @Test
    void completionReturnsStable403503And303Outcomes() {
        Fixture invalidFixture = fixture();
        when(invalidFixture.preAuthService().resolveChallengeNavigation(
                        RiskScope.USER,
                        PREAUTH_TOKEN))
                .thenReturn(Optional.empty());
        ResponseEntity<NetworkRiskEdgeController.ChallengeCompletionResponse> invalid =
                invalidFixture.controller().completeUserChallenge(
                        CHALLENGE_REF,
                        requestWithCookie());

        Fixture unavailableFixture = fixture();
        when(unavailableFixture.preAuthService().resolveChallengeNavigation(
                        RiskScope.USER,
                        PREAUTH_TOKEN))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        ResponseEntity<NetworkRiskEdgeController.ChallengeCompletionResponse> unavailable =
                unavailableFixture.controller().completeUserChallenge(
                        CHALLENGE_REF,
                        requestWithCookie());

        Fixture successFixture = fixture();
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(successFixture.preAuthService().resolveChallengeNavigation(
                        RiskScope.USER,
                        PREAUTH_TOKEN))
                .thenReturn(Optional.of(access));
        when(successFixture.challengeService().consumeAndTrust(
                        eq(access),
                        eq(CHALLENGE_REF),
                        any()))
                .thenReturn(true);
        ResponseEntity<NetworkRiskEdgeController.ChallengeCompletionResponse> success =
                successFixture.controller().completeUserChallenge(
                        CHALLENGE_REF,
                        requestWithCookie());

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invalid.getBody()).isNotNull();
        assertThat(invalid.getBody().code()).isEqualTo("RISK_CHALLENGE_INVALID");
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().code())
                .isEqualTo("RISK_CHALLENGE_UNAVAILABLE");
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(success.getHeaders().getLocation())
                .isEqualTo(URI.create("/pages/risk/challenge-complete"));
    }

    @Test
    void bootstrapReturns503ForStorageFailureButPropagatesUnexpectedProgramError() {
        Fixture unavailableFixture = fixture();
        when(unavailableFixture.bootstrapService().bootstrap(
                        eq(RiskScope.USER),
                        any(),
                        eq("device-test"),
                        any(),
                        eq(false)))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        ResponseEntity<NetworkRiskEdgeController.BootstrapResponse> unavailable =
                unavailableFixture.controller().bootstrapUser(
                        "device-test",
                        "ANDROID",
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse());

        Fixture brokenFixture = fixture();
        when(brokenFixture.bootstrapService().bootstrap(
                        eq(RiskScope.USER),
                        any(),
                        eq("device-test"),
                        any(),
                        eq(false)))
                .thenThrow(new IllegalArgumentException("unexpected program error"));

        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().code())
                .isEqualTo("RISK_ASSESSMENT_UNAVAILABLE");
        assertThatThrownBy(() -> brokenFixture.controller().bootstrapUser(
                        "device-test",
                        "ANDROID",
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture() {
        PreAuthRiskBootstrapService bootstrapService =
                mock(PreAuthRiskBootstrapService.class);
        PreAuthService preAuthService = mock(PreAuthService.class);
        RiskChallengeService challengeService = mock(RiskChallengeService.class);
        RiskRequestContextResolver contextResolver =
                mock(RiskRequestContextResolver.class);
        PreAuthTransport transport = mock(PreAuthTransport.class);
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation()));
        when(transport.read(any(), any())).thenReturn(PREAUTH_TOKEN);
        NetworkRiskEdgeController controller = new NetworkRiskEdgeController(
                bootstrapService,
                preAuthService,
                challengeService,
                contextResolver,
                transport,
                properties,
                mock(AuthCookieWriter.class),
                mock(AdminCookieWriter.class),
                mock(WebRtcVerificationTransport.class),
                mock(WebRtcMetrics.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(
                controller,
                bootstrapService,
                preAuthService,
                challengeService);
    }

    private static MockHttpServletRequest requestWithCookie() {
        return new MockHttpServletRequest();
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                NOW);
    }

    private record Fixture(
            NetworkRiskEdgeController controller,
            PreAuthRiskBootstrapService bootstrapService,
            PreAuthService preAuthService,
            RiskChallengeService challengeService) {
    }
}
