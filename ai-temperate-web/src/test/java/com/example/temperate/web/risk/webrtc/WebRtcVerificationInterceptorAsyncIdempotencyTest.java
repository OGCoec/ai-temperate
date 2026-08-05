package com.example.temperate.web.risk.webrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证 WebRTC 拦截器的请求级幂等标记与 NetworkRisk 标记彼此独立且不能跨安全边界复用。
 */
class WebRtcVerificationInterceptorAsyncIdempotencyTest {

    private static final String HTTP_IP = "198.51.100.10";
    private static final String SENSITIVE_TEST_TOKEN = "TEST_PREAUTH_TOKEN_MUST_NOT_APPEAR";

    @Test
    void reusesVerifiedResultAcrossRepeatedAsyncDispatchesWithoutDuplicateMetric()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP))
                .thenReturn(
                        WebRtcVerificationDecision.verified(List.of(HTTP_IP)),
                        WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        request.addHeader("Cookie", "__Host-ait-admin-preauth=" + SENSITIVE_TEST_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start(WebRtcVerificationInterceptor.class)) {
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            request.setDispatcherType(DispatcherType.ASYNC);
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            assertThat(logs.joined())
                    .contains("event=webrtc_async_result_reused")
                    .doesNotContain(HTTP_IP, SENSITIVE_TEST_TOKEN);
        }

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        verify(fixture.contextResolver(), times(1)).resolve(request);
        verify(fixture.service(), times(1)).inspect(access, HTTP_IP);
        assertThat(fixture.meterRegistry()
                        .get("webrtc_interceptor_total")
                        .tags(
                                "scope", "admin",
                                "decision", "allowed",
                                "platform", "h5",
                                "mode", "enforce")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
    }

    @Test
    void pendingAdmissionIsNotRevokedWhenReportFailsDuringTheSameAsyncRequest()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP))
                .thenReturn(
                        WebRtcVerificationDecision.pending(
                                7L,
                                Instant.parse("2026-08-04T12:00:20Z")),
                        WebRtcVerificationDecision.failed());
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        verify(fixture.service(), times(1)).inspect(access, HTTP_IP);
        assertThat(fixture.meterRegistry()
                        .get("webrtc_interceptor_total")
                        .tags(
                                "scope", "admin",
                                "decision", "pending_allowed",
                                "platform", "h5",
                                "mode", "enforce")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("securityBoundaryChanges")
    void doesNotReuseVerifiedResultAcrossSecurityBoundaryChanges(
            String description,
            BoundaryChange change) throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP))
                .thenReturn(
                        WebRtcVerificationDecision.verified(List.of(HTTP_IP)),
                        WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);

        assertThat(fixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        change.apply(request);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, secondResponse, new Object()))
                .isTrue();
        assertThat(secondResponse.getStatus()).isEqualTo(200);

        verify(fixture.contextResolver(), times(2)).resolve(request);
        verify(fixture.service(), times(2)).inspect(access, HTTP_IP);
    }

    @Test
    void evaluatesAsyncDispatchWithoutSuccessfulMarker() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP))
                .thenReturn(WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        verify(fixture.contextResolver(), times(1)).resolve(request);
        verify(fixture.service(), times(1)).inspect(access, HTTP_IP);
    }

    @Test
    void evaluatesIndependentHttpRequestsSeparately() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP))
                .thenReturn(WebRtcVerificationDecision.verified(List.of(HTTP_IP)));
        MockHttpServletRequest first = request(
                "POST",
                "/api/auth/login/code/turnstile");
        MockHttpServletRequest second = request(
                "POST",
                "/api/auth/login/code/turnstile");
        first.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        second.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);

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

        verify(fixture.contextResolver(), times(1)).resolve(first);
        verify(fixture.contextResolver(), times(1)).resolve(second);
        verify(fixture.service(), times(2)).inspect(access, HTTP_IP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedVerificationCases")
    void doesNotMarkFailedVerificationAsReusable(
            String description,
            WebRtcVerificationDecision decision,
            int expectedStatus) throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, HTTP_IP)).thenReturn(decision);
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, firstResponse, new Object()))
                .isFalse();
        assertThat(firstResponse.getStatus()).isEqualTo(expectedStatus);

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        assertThat(fixture.interceptor().preHandle(request, secondResponse, new Object()))
                .isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(expectedStatus);

        verify(fixture.contextResolver(), times(2)).resolve(request);
        verify(fixture.service(), times(2)).inspect(access, HTTP_IP);
    }

    @Test
    void networkRiskMarkerDoesNotReplaceWebRtcMarker() throws Exception {
        Fixture webRtcFixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        NetworkRiskInterceptor networkRiskInterceptor = networkRiskInterceptor(
                webRtcFixture.properties(),
                access,
                webRtcFixture.observation(),
                RiskDecision.ALLOW);
        when(webRtcFixture.service().inspect(access, HTTP_IP))
                .thenReturn(WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");

        assertThat(networkRiskInterceptor.preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(webRtcFixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        verify(webRtcFixture.service(), times(1)).inspect(access, HTTP_IP);
    }

    @Test
    void webRtcMarkerDoesNotReplaceNetworkRiskMarker() throws Exception {
        Fixture webRtcFixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(webRtcFixture.service().inspect(access, HTTP_IP))
                .thenReturn(WebRtcVerificationDecision.verified(List.of(HTTP_IP)));
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/auth/login/complete");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);

        assertThat(webRtcFixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        NetworkRiskInterceptor networkRiskInterceptor = networkRiskInterceptor(
                webRtcFixture.properties(),
                access,
                webRtcFixture.observation(),
                RiskDecision.BLOCK);
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(networkRiskInterceptor.preHandle(request, response, new Object()))
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(403);
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

    private static Stream<Arguments> failedVerificationCases() {
        return Stream.of(
                Arguments.of(
                        "未获取公网地址",
                        WebRtcVerificationDecision.failed(),
                        428),
                Arguments.of(
                        "公网地址不匹配",
                        WebRtcVerificationDecision.mismatch(List.of("203.0.113.9")),
                        403));
    }

    private static Fixture fixture(NetworkRiskMode mode) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(mode);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                List.of(URI.create("stun:stun.cloudflare.com:3478")),
                8,
                ""));
        WebRtcVerificationService service = mock(WebRtcVerificationService.class);
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        TrustedNetworkObservation observation = observation();
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebRtcVerificationInterceptor interceptor = new WebRtcVerificationInterceptor(
                properties,
                service,
                contextResolver,
                new ObjectMapper(),
                new WebRtcMetrics(meterRegistry),
                new WebRtcVerificationTransport());
        return new Fixture(
                interceptor,
                service,
                contextResolver,
                meterRegistry,
                properties,
                observation);
    }

    private static NetworkRiskInterceptor networkRiskInterceptor(
            NetworkRiskProperties properties,
            PreAuthAccess access,
            TrustedNetworkObservation observation,
            RiskDecision decision) {
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(any(), any(), any()))
                .thenReturn(Optional.of(access));
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation));
        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        when(assessmentService.assess(access, observation))
                .thenReturn(Mono.just(new RiskAssessment(
                        decision,
                        decision == RiskDecision.ALLOW ? 100 : 10,
                        false,
                        0,
                        HmacIdentifier.fromProtectedValue("I".repeat(43)),
                        HmacIdentifier.fromProtectedValue("C".repeat(43)))));
        return new NetworkRiskInterceptor(
                properties,
                preAuthService,
                assessmentService,
                mock(RiskChallengeService.class),
                contextResolver,
                mock(PreAuthTransport.class),
                new ObjectMapper(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                HTTP_IP,
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
            WebRtcVerificationInterceptor interceptor,
            WebRtcVerificationService service,
            RiskRequestContextResolver contextResolver,
            SimpleMeterRegistry meterRegistry,
            NetworkRiskProperties properties,
            TrustedNetworkObservation observation) {
    }

    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(
                Logger logger,
                Level previousLevel,
                ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.previousLevel = previousLevel;
            this.appender = appender;
        }

        private static LogCapture start(Class<?> type) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            return new LogCapture(logger, previousLevel, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
