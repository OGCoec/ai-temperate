package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证网络风险拦截器只对同一请求、同一安全边界的 ASYNC 分派复用首次放行结果。
 *
 * <p>测试故意把第二次评估配置为失败，以保证移除请求级幂等标记后会稳定复现虚假 428，
 * 而不是只依赖 Mockito 调用次数获得表面覆盖。</p>
 */
class NetworkRiskInterceptorAsyncIdempotencyTest {

    @Test
    void reusesAllowedResultAcrossRepeatedAsyncDispatches() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.of(access));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(fixture.observation()));
        when(fixture.assessmentService().assess(access, fixture.observation()))
                .thenReturn(
                        Mono.just(allowAssessment()),
                        Mono.error(new PreAuthRequiredException()));
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
        verify(fixture.contextResolver(), times(1)).resolve(request);
        verify(fixture.assessmentService(), times(1))
                .assess(access, fixture.observation());
        verify(fixture.challengeService(), never()).issue(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("securityBoundaryChanges")
    void doesNotReuseWhenSecurityBoundaryChanges(
            String description,
            BoundaryChange change) throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.of(access));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(fixture.observation()));
        when(fixture.assessmentService().assess(access, fixture.observation()))
                .thenReturn(Mono.just(allowAssessment()));
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");

        assertThat(fixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        change.apply(request);
        assertThat(fixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        verify(fixture.contextResolver(), times(2)).resolve(request);
        verify(fixture.assessmentService(), times(2))
                .assess(access, fixture.observation());
    }

    @Test
    void evaluatesIndependentHttpRequestsSeparately() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.of(access));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(fixture.observation()));
        when(fixture.assessmentService().assess(access, fixture.observation()))
                .thenReturn(Mono.just(allowAssessment()));

        MockHttpServletRequest first = request("POST", "/api/auth/login/code/turnstile");
        MockHttpServletRequest second = request("POST", "/api/auth/login/code/turnstile");

        assertThat(fixture.interceptor().preHandle(
                        first,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();
        assertThat(fixture.interceptor().preHandle(
                        second,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        verify(fixture.preAuthService(), times(2)).resolve(any(), any(), any());
        verify(fixture.contextResolver(), times(1)).resolve(first);
        verify(fixture.contextResolver(), times(1)).resolve(second);
        verify(fixture.assessmentService(), times(2))
                .assess(access, fixture.observation());
    }

    @Test
    void doesNotMarkPreAuthRejectionAsReusable() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.empty());
        MockHttpServletRequest request = request("POST", "/api/admin/auth/login/complete");

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, firstResponse, new Object()))
                .isFalse();
        assertThat(firstResponse.getStatus()).isEqualTo(428);

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, secondResponse, new Object()))
                .isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(428);

        verify(fixture.preAuthService(), times(2)).resolve(any(), any(), any());
        verify(fixture.assessmentService(), never()).assess(any(), any());
    }

    @Test
    void doesNotMarkContextFailureAsReusable() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.of(access));
        when(fixture.contextResolver().resolve(any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = request("POST", "/api/admin/auth/login/complete");

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, firstResponse, new Object()))
                .isFalse();
        assertThat(firstResponse.getStatus()).isEqualTo(503);

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, secondResponse, new Object()))
                .isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(503);

        verify(fixture.contextResolver(), times(2)).resolve(request);
        verify(fixture.assessmentService(), never()).assess(any(), any());
    }

    @Test
    void doesNotMarkBlockDecisionAsReusable() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE, Optional.of(access));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(fixture.observation()));
        RiskAssessment blocked = new RiskAssessment(
                RiskDecision.BLOCK,
                10,
                false,
                0,
                HmacIdentifier.fromProtectedValue("B".repeat(43)),
                HmacIdentifier.fromProtectedValue("D".repeat(43)));
        when(fixture.assessmentService().assess(access, fixture.observation()))
                .thenReturn(Mono.just(blocked));
        MockHttpServletRequest request = request("POST", "/api/admin/auth/login/complete");

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, firstResponse, new Object()))
                .isFalse();
        assertThat(firstResponse.getStatus()).isEqualTo(403);

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, secondResponse, new Object()))
                .isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(403);

        verify(fixture.assessmentService(), times(2))
                .assess(access, fixture.observation());
        verify(fixture.challengeService(), never()).issue(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reusableBypassCases")
    void reusesObserveDisabledAndOptionsOutcomes(
            String description,
            NetworkRiskMode mode,
            String method,
            int expectedResolveCalls) throws Exception {
        Fixture fixture = fixture(mode, Optional.empty());
        MockHttpServletRequest request = request(
                method,
                "/api/admin/auth/login/complete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        verify(fixture.preAuthService(), times(expectedResolveCalls))
                .resolve(any(), any(), any());
        verify(fixture.contextResolver(), never()).resolve(any());
        verify(fixture.assessmentService(), never()).assess(any(), any());
    }

    private static Stream<Arguments> securityBoundaryChanges() {
        return Stream.of(
                Arguments.of(
                        "HTTP method 改变",
                        (BoundaryChange) request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            request.setMethod("GET");
                        }),
                Arguments.of(
                        "同作用域 URI 改变",
                        (BoundaryChange) request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            request.setRequestURI("/api/admin/auth/login/other");
                        }),
                Arguments.of(
                        "风险作用域改变",
                        (BoundaryChange) request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            request.setRequestURI("/api/auth/login/code/turnstile");
                        }),
                Arguments.of(
                        "ERROR 分派",
                        (BoundaryChange) request -> request.setDispatcherType(
                                DispatcherType.ERROR)),
                Arguments.of(
                        "FORWARD 分派",
                        (BoundaryChange) request -> request.setDispatcherType(
                                DispatcherType.FORWARD)),
                Arguments.of(
                        "INCLUDE 分派",
                        (BoundaryChange) request -> request.setDispatcherType(
                                DispatcherType.INCLUDE)));
    }

    private static Stream<Arguments> reusableBypassCases() {
        return Stream.of(
                Arguments.of("OBSERVE 缺少 PreAuth 后放行", NetworkRiskMode.OBSERVE, "POST", 1),
                Arguments.of("DISABLED 直接放行", NetworkRiskMode.DISABLED, "POST", 0),
                Arguments.of("OPTIONS 预检放行", NetworkRiskMode.ENFORCE, "OPTIONS", 0));
    }

    private static Fixture fixture(
            NetworkRiskMode mode,
            Optional<PreAuthAccess> resolvedAccess) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(mode);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(any(), any(), any())).thenReturn(resolvedAccess);
        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        RiskChallengeService challengeService = mock(RiskChallengeService.class);
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        TrustedNetworkObservation observation = observation();
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
                preAuthService,
                assessmentService,
                challengeService,
                contextResolver,
                observation);
    }

    private static RiskAssessment allowAssessment() {
        return new RiskAssessment(
                RiskDecision.ALLOW,
                100,
                false,
                0,
                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                HmacIdentifier.fromProtectedValue("C".repeat(43)));
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-26T16:06:24Z"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("X-Device-Installation-Id", "async-idempotency-device");
        return request;
    }

    @FunctionalInterface
    private interface BoundaryChange {

        void apply(MockHttpServletRequest request);
    }

    private record Fixture(
            NetworkRiskInterceptor interceptor,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            RiskChallengeService challengeService,
            RiskRequestContextResolver contextResolver,
            TrustedNetworkObservation observation) {
    }
}
