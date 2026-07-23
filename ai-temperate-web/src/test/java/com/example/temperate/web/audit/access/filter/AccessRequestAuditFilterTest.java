package com.example.temperate.web.audit.access.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.service.AccessAuditEventService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 验证访问审计过滤器仅覆盖受保护 API，并只传递模板路由和已认证主体而不读取原始 URI 参数。
 */
class AccessRequestAuditFilterTest {

    @Test
    void recordsAProtectedRequestAfterTheHandlerCompletes() throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        org.mockito.Mockito.when(resolver.resolve(any()))
                .thenReturn(java.util.Optional.of("203.0.113.77"));
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/AAAAAAAAJxE");
        request.setRemoteAddr("203.0.113.77");
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/users/{id}");
            servletRequest.setAttribute(
                    AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                    new SessionPrincipal(10001L, "AAAAAAAAJxE", "Alice"));
            ((MockHttpServletResponse) servletResponse).setStatus(204);
        });

        ArgumentCaptor<AccessAuditCommand> captor = ArgumentCaptor.forClass(AccessAuditCommand.class);
        verify(service).record(captor.capture());
        AccessAuditCommand command = captor.getValue();
        assertThat(command.userId()).isEqualTo(10001L);
        assertThat(command.routeTemplate()).isEqualTo("/api/users/{id}");
        assertThat(command.statusCode()).isEqualTo(204);
        assertThat(command.canonicalClientIp()).isEqualTo("203.0.113.77");
        assertThat(response.getHeader(AccessRequestAuditFilter.TRACE_HEADER)).isNotBlank();
    }

    @Test
    void excludesPublicAuthenticationAndHealthRoutes() throws ServletException, IOException {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);

        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/auth/login"),
                new MockHttpServletResponse(),
                new MockFilterChain());
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/health"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        verify(service, never()).record(any());
    }
}
