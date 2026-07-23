package com.example.temperate.web.auth.diagnostic.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
}
