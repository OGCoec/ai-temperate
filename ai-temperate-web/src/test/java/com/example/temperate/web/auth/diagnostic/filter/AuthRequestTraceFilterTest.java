package com.example.temperate.web.auth.diagnostic.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证认证诊断过滤器只输出脱敏关联信息，并为认证响应稳定写入追踪标识。
 */
class AuthRequestTraceFilterTest {

    @Test
    void tracesAuthRequestAndRecordsOnlyCookieByteCount() throws Exception {
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("203.0.113.7"));
        AuthRequestTraceFilter filter = new AuthRequestTraceFilter(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/auth/register/turnstile");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("CF-Ray", "test-ray-ord");
        request.addHeader("X-Turnstile-Attempt-Id", "attempt_01HF7YAT00TESTONLY");
        request.addHeader("Cookie", "register_flow_token=secret; cf_clearance=secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(403);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(AuthRequestTraceFilter.TRACE_HEADER))
                .satisfies(value -> UUID.fromString(value));
        assertThat(request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE)).isNotNull();
        assertThat(request.getAttribute(AuthRequestTraceFilter.INBOUND_CF_RAY_ATTRIBUTE))
                .isEqualTo("test-ray-ord");
        assertThat(request.getAttribute(AuthRequestTraceFilter.ATTEMPT_ATTRIBUTE))
                .isEqualTo("attempt_01HF7YAT00TESTONLY");
        assertThat(request.getAttribute(AuthRequestTraceFilter.COOKIE_BYTES_ATTRIBUTE))
                .isEqualTo("register_flow_token=secret; cf_clearance=secret"
                        .getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void leavesNonAuthRequestOutsideTheDiagnosticBoundary() throws Exception {
        AuthRequestTraceFilter filter = new AuthRequestTraceFilter(
                mock(TrustedClientIpResolver.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(AuthRequestTraceFilter.TRACE_HEADER)).isNull();
        assertThat(request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE)).isNull();
    }

    @Test
    void logsAsyncRequestOnceAfterCompletionWithFinalStatusAndAlignedFields()
            throws Exception {
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("2001:4860:4860::8888"));
        AuthRequestTraceFilter filter = new AuthRequestTraceFilter(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/admin/auth/login/complete");
        request.setRemoteAddr("198.18.1.183");
        request.setAsyncSupported(true);
        request.addHeader("Cookie", "admin_login_flow=redacted");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(AuthRequestTraceFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    servletRequest.startAsync(servletRequest, servletResponse));

            assertThat(appender.list).isEmpty();
            response.setStatus(400);
            request.getAsyncContext().complete();

            assertThat(appender.list).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(
                                    "status=400",
                                    "cookieHeaderBytes=25",
                                    "remoteAddressFamily=IPV4",
                                    "resolvedAddressFamily=IPV6",
                                    "resolvedClientDiffers=true"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void treatsEquivalentIpv6TextFormsAsTheSameResolvedClient() throws Exception {
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("2001:db8::1"));
        AuthRequestTraceFilter filter = new AuthRequestTraceFilter(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/auth/login/password");
        request.setRemoteAddr("2001:0DB8:0000:0000:0000:0000:0000:0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(AuthRequestTraceFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, new MockFilterChain());

            assertThat(appender.list).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(
                                    "remoteAddressFamily=IPV6",
                                    "resolvedAddressFamily=IPV6",
                                    "resolvedClientDiffers=false"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
