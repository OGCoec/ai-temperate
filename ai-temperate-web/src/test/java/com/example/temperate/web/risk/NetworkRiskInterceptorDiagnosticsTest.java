package com.example.temperate.web.risk;

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
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import reactor.core.publisher.Mono;

/**
 * 验证网络风险拦截器记录稳定调用序号、精确 428 原因，并只在同请求同路径的 ASYNC 分派复用放行结果。
 */
class NetworkRiskInterceptorDiagnosticsTest {

    @Test
    void recordsMissingPreAuthAsFirstRequestRejection() throws Exception {
        Fixture fixture = fixture(Optional.empty());
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isFalse();

            assertThat(logs.joined())
                    .contains(
                            "event=network_risk_prehandle_enter",
                            "traceId=trace-diagnostics",
                            "invocationNo=1",
                            "dispatcherType=REQUEST",
                            "event=network_risk_rejected",
                            "reason=preauth_missing",
                            "status=428");
            assertThat(response.getStatus()).isEqualTo(428);
            assertThat(response.getContentAsString())
                    .contains("\"code\":\"PREAUTH_REQUIRED\"");
        }
    }

    @Test
    void reusesSuccessfulAssessmentOnSecondAsyncDispatchWithSameTrace() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(Optional.of(access));
        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-26T16:06:24Z"));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(observation));
        when(fixture.assessmentService().assess(access, observation))
                .thenReturn(
                        Mono.just(new RiskAssessment(
                                RiskDecision.ALLOW,
                                100,
                                false,
                                0,
                                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                                HmacIdentifier.fromProtectedValue("C".repeat(43)))),
                        Mono.error(new PreAuthRequiredException()));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            request.setDispatcherType(DispatcherType.ASYNC);
            WebAsyncManager asyncManager = mock(WebAsyncManager.class);
            when(asyncManager.hasConcurrentResult()).thenReturn(true);
            request.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE, asyncManager);
            assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                    .isTrue();

            assertThat(logs.joined())
                    .contains(
                            "traceId=trace-diagnostics invocationNo=1 dispatcherType=REQUEST",
                            "traceId=trace-diagnostics invocationNo=2 dispatcherType=ASYNC",
                            "hasConcurrentResult=true",
                            "event=network_risk_async_result_reused")
                    .doesNotContain(
                            "reason=preauth_concurrent_expiry",
                            "status=428");
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEmpty();
            verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
            verify(fixture.contextResolver(), times(1)).resolve(request);
            verify(fixture.assessmentService(), times(1)).assess(access, observation);
        }
    }

    @Test
    void evaluatesAsyncDispatchWithoutSuccessfulRequestMarker() throws Exception {
        Fixture fixture = fixture(Optional.empty());
        MockHttpServletRequest request = request();
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(428);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"PREAUTH_REQUIRED\"");
        verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
    }

    @Test
    void doesNotReuseSuccessfulAssessmentAfterAsyncPathChange() throws Exception {
        PreAuthAccess access = mock(PreAuthAccess.class);
        Fixture fixture = fixture(Optional.of(access));
        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-26T16:06:24Z"));
        when(fixture.contextResolver().resolve(any()))
                .thenReturn(Optional.of(observation));
        when(fixture.assessmentService().assess(access, observation))
                .thenReturn(
                        Mono.just(new RiskAssessment(
                                RiskDecision.ALLOW,
                                100,
                                false,
                                0,
                                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                                HmacIdentifier.fromProtectedValue("C".repeat(43)))),
                        Mono.error(new PreAuthRequiredException()));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isTrue();

        request.setDispatcherType(DispatcherType.ASYNC);
        request.setRequestURI("/api/admin/auth/login/other");
        assertThat(fixture.interceptor().preHandle(request, response, new Object()))
                .isFalse();

        assertThat(response.getStatus()).isEqualTo(428);
        verify(fixture.assessmentService(), times(2)).assess(access, observation);
    }

    private static Fixture fixture(Optional<PreAuthAccess> resolvedAccess) {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(any(), any(), any())).thenReturn(resolvedAccess);
        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        NetworkRiskInterceptor interceptor = new NetworkRiskInterceptor(
                properties,
                preAuthService,
                assessmentService,
                mock(RiskChallengeService.class),
                contextResolver,
                mock(PreAuthTransport.class),
                new ObjectMapper(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
        return new Fixture(
                interceptor,
                preAuthService,
                assessmentService,
                contextResolver);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/admin/auth/login/complete");
        request.setAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE, "trace-diagnostics");
        request.addHeader("X-Device-Installation-Id", "sensitive-device-id");
        request.addHeader("Cookie", "__Host-ait-admin-preauth=sensitive-preauth-token");
        return request;
    }

    private record Fixture(
            NetworkRiskInterceptor interceptor,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            RiskRequestContextResolver contextResolver) {
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
            Logger logger = (Logger) LoggerFactory.getLogger(NetworkRiskInterceptor.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            return new LogCapture(logger, previousLevel, appender);
        }

        private String joined() {
            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            String joined = String.join("\n", messages);
            assertThat(joined)
                    .doesNotContain(
                            "sensitive-device-id",
                            "sensitive-preauth-token",
                            "198.51.100.10");
            return joined;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
