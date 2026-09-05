package com.example.temperate.web.risk.webrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 WebRTC 拦截器的模式化阻断、结构化错误和同请求 ASYNC 结果复用边界。
 */
class WebRtcVerificationInterceptorTest {

    @Test
    void requiredAndPendingAllowEveryBusinessHttpMethodImmediately() throws Exception {
        for (WebRtcVerificationDecision decision : List.of(
                WebRtcVerificationDecision.required(),
                WebRtcVerificationDecision.pending(
                        3L,
                        Instant.parse("2026-07-25T12:00:20Z")))) {
            Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
            when(fixture.service().inspect(any(), eq("8.8.8.8")))
                    .thenReturn(decision);

            for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                MockHttpServletRequest request = new MockHttpServletRequest(
                        method,
                        "/api/catalog/items");
                request.addHeader("X-Client-Platform", "H5");
                request.setAttribute(
                        NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                        mock(PreAuthAccess.class));
                MockHttpServletResponse response = new MockHttpServletResponse();

                assertThat(fixture.interceptor().preHandle(
                                request,
                                response,
                                new Object()))
                        .as(
                                "%s must be allowed while WebRTC is %s",
                                method,
                                decision.verificationState())
                        .isTrue();
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }
    }

    @Test
    void enforceModeAllowsPendingPreAuthWithoutWaitingForReport() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.pending(
                        7L,
                        Instant.parse("2026-07-25T12:00:20Z")));
        MockHttpServletRequest request = request();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(
                request,
                response,
                new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(WebRtcVerificationTransport.STATE_HEADER))
                .isEqualTo("PENDING");
        assertThat(response.getHeader(WebRtcVerificationTransport.GENERATION_HEADER))
                .isEqualTo("7");
        assertThat(fixture.meterRegistry()
                        .get("webrtc_interceptor_total")
                        .tags(
                                "scope", "user",
                                "decision", "pending_allowed",
                                "platform", "h5",
                                "mode", "enforce")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
    }

    @Test
    void h5OAuthCompletionRejectsPendingUntilReportIsVerified() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.pending(
                        7L,
                        Instant.parse("2026-07-25T12:00:20Z")));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/oauth2/complete");
        request.addHeader("X-Client-Platform", "H5");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString())
                .contains("WEBRTC_VERIFICATION_PENDING");
    }

    @Test
    void h5OAuthCompletionAllowsOnlyVerifiedState() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.verified(List.of("8.8.8.8")));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/oauth2/complete");
        request.addHeader("X-Client-Platform", "H5");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void h5OAuthCompletionRejectsFailedReportState() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.failed());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/oauth2/complete");
        request.addHeader("X-Client-Platform", "H5");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString())
                .contains("WEBRTC_VERIFICATION_FAILED");
    }

    @Test
    void androidNativeOAuthCompletionKeepsExistingPendingBehavior() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.pending(
                        7L,
                        Instant.parse("2026-07-25T12:00:20Z")));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/oauth2/google/native/complete");
        request.addHeader("X-Client-Platform", "ANDROID");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void recordsGenerationChangeFromTheAtomicNetworkAssessment() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.required(
                        4L,
                        Instant.parse("2026-07-25T12:00:08Z")));
        MockHttpServletRequest request = request();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        request.setAttribute(
                NetworkRiskInterceptor.WEBRTC_GENERATION_CHANGED_ATTRIBUTE,
                Boolean.TRUE);

        assertThat(fixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();

        assertThat(fixture.meterRegistry()
                        .get("webrtc_state_transition_total")
                        .tags(
                                "scope", "user",
                                "transition", "generation_changed",
                                "platform", "h5",
                                "reason", "network_changed",
                                "mode", "enforce")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
        assertThat(request.getAttribute(
                NetworkRiskInterceptor.WEBRTC_GENERATION_CHANGED_ATTRIBUTE))
                .isNull();
    }

    @Test
    void observeModeRecordsButAllowsMismatch() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.OBSERVE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.mismatch(
                        List.of("1.1.1.1")));
        MockHttpServletRequest request = request();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));

        assertThat(fixture.interceptor().preHandle(
                        request,
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();
    }

    @Test
    void enforceModeReturns428WithoutLeakWordingForIncompleteIpFamily()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.failed(
                        9L,
                        PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE,
                        List.of("2606:4700:4700::1111")));
        MockHttpServletRequest request = request();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        request.setAttribute(
                AuthRequestTraceFilter.TRACE_ATTRIBUTE,
                "123e4567-e89b-42d3-a456-426614174000");
        request.setAttribute(
                AuthRequestTraceFilter.CLIENT_REQUEST_ATTRIBUTE,
                "123e4567-e89b-42d3-a456-426614174001");
        request.setAttribute(
                AuthRequestTraceFilter.PAGE_INSTANCE_ATTRIBUTE,
                "123e4567-e89b-42d3-a456-426614174003");
        request.setAttribute(
                AuthRequestTraceFilter.WEBRTC_PROBE_RUN_ATTRIBUTE,
                "123e4567-e89b-42d3-a456-426614174002");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isFalse();
            assertThat(logs.joined())
                    .contains(
                            "event=webrtc_interceptor_rejected",
                            "status=428",
                            "errorCode=WEBRTC_IP_FAMILY_INCOMPLETE",
                            "generation=9",
                            "failureReason=IP_FAMILY_INCOMPLETE",
                            "pageInstanceId=123e4567-e89b-42d3-a456-426614174003",
                            "probeRunId=123e4567-e89b-42d3-a456-426614174002")
                    .doesNotContain("2606:4700:4700::1111", "8.8.8.8");
        }
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString())
                .contains(
                        "WEBRTC_IP_FAMILY_INCOMPLETE",
                        "同协议族",
                        "\"verificationState\":\"FAILED\"",
                        "2606:4700:4700::1111")
                .doesNotContain("泄漏", "WEBRTC_IP_MISMATCH");
    }


    @Test
    void reusesVerifiedResultOnAsyncDispatchWithoutDuplicateInspectionOrMetric()
            throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, "8.8.8.8"))
                .thenReturn(
                        WebRtcVerificationDecision.verified(List.of("8.8.8.8")),
                        WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request();
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            request.setDispatcherType(DispatcherType.ASYNC);
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            assertThat(logs.joined())
                    .contains("event=webrtc_async_result_reused")
                    .doesNotContain("8.8.8.8");
        }
        assertThat(response.getStatus()).isEqualTo(200);
        verify(fixture.contextResolver(), times(1)).resolve(request);
        verify(fixture.service(), times(1)).inspect(access, "8.8.8.8");
        assertThat(fixture.meterRegistry()
                        .get("webrtc_interceptor_total")
                        .tags(
                                "scope", "user",
                                "decision", "allowed",
                                "platform", "h5",
                                "mode", "enforce")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
    }

    @Test
    void evaluatesAsyncDispatchWithoutSuccessfulRequestMarker() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, "8.8.8.8"))
                .thenReturn(WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(WebRtcVerificationTransport.STATE_HEADER))
                .isEqualTo("REQUIRED");
        verify(fixture.service(), times(1)).inspect(access, "8.8.8.8");
    }

    @Test
    void doesNotReuseVerifiedResultAfterAsyncPathChange() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, "8.8.8.8"))
                .thenReturn(
                        WebRtcVerificationDecision.verified(List.of("8.8.8.8")),
                        WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request();
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        request.setDispatcherType(DispatcherType.ASYNC);
        request.setRequestURI("/api/catalog/other");
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        verify(fixture.service(), times(2)).inspect(access, "8.8.8.8");
    }

    @Test
    void wechatRequestWithValidPreAuthIsAllowedEvenIfWebRtcTimedOut() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        PreAuthAccess access = mock(PreAuthAccess.class);
        when(fixture.service().inspect(access, "8.8.8.8"))
                .thenReturn(WebRtcVerificationDecision.failed(
                        1L,
                        PreAuthWebRtcFailureReason.START_TIMEOUT,
                        List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/phone-country");
        request.addHeader("X-Client-Platform", "WECHAT_MINI_PROGRAM");
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(fixture.service(), times(1)).inspect(access, "8.8.8.8");
    }

    @Test
    void wechatRequestWithoutPreAuthIsRejectedToPreventHeaderSpoofing() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login/password");
        request.addHeader("X-Client-Platform", "WECHAT_MINI_PROGRAM");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString()).contains("PREAUTH_REQUIRED");
    }

    private static Fixture fixture(NetworkRiskMode mode) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(mode);
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                Duration.ofSeconds(15),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                ""));
        WebRtcVerificationService service = mock(WebRtcVerificationService.class);
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        when(contextResolver.resolve(any())).thenReturn(Optional.of(
                new TrustedNetworkObservation(
                        "8.8.8.8",
                        "US",
                        15169L,
                        null,
                        null,
                        Instant.parse("2026-07-25T12:00:00Z"))));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebRtcVerificationInterceptor interceptor = new WebRtcVerificationInterceptor(
                properties,
                service,
                contextResolver,
                new ObjectMapper(),
                new WebRtcMetrics(meterRegistry),
                new WebRtcVerificationTransport());
        return new Fixture(interceptor, service, contextResolver, meterRegistry);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/catalog/items");
        request.addHeader("X-Client-Platform", "H5");
        return request;
    }

    private record Fixture(
            WebRtcVerificationInterceptor interceptor,
            WebRtcVerificationService service,
            RiskRequestContextResolver contextResolver,
            SimpleMeterRegistry meterRegistry) {
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

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    WebRtcVerificationInterceptor.class);
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
