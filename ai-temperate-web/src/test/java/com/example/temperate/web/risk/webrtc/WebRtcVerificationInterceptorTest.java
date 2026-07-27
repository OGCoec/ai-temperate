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
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
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
    void enforceModeReturns428ForUnverifiedPreAuth() throws Exception {
        Fixture fixture = fixture(NetworkRiskMode.ENFORCE);
        when(fixture.service().inspect(any(), eq("8.8.8.8")))
                .thenReturn(WebRtcVerificationDecision.required());
        MockHttpServletRequest request = request();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = fixture.interceptor().preHandle(
                request,
                response,
                new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("WEBRTC_VERIFICATION_REQUIRED")
                .contains("/api/_edge/webrtc/start")
                .contains("15000");
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
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(428);
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
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(428);
        verify(fixture.service(), times(2)).inspect(access, "8.8.8.8");
    }

    private static Fixture fixture(NetworkRiskMode mode) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(mode);
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
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
                new WebRtcMetrics(meterRegistry));
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
