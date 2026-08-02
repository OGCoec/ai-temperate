package com.example.temperate.web.audit.access.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.service.AccessAuditEventService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockAsyncContext;
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
        when(resolver.resolve(any())).thenReturn(Optional.of("203.0.113.77"));
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
    void waitsForAsyncCompletionAndRecordsTheFinalStateOnce() throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of("203.0.113.78"));
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/responses");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/ai/conversations/{conversationId}/responses");
            servletRequest.setAttribute(
                    AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                    new SessionPrincipal(10002L, "AAAAAAAAJxF", "Bob"));
            servletRequest.startAsync(servletRequest, servletResponse);
        });

        verify(service, never()).record(any());
        response.setStatus(206);
        request.getAsyncContext().complete();

        ArgumentCaptor<AccessAuditCommand> captor =
                ArgumentCaptor.forClass(AccessAuditCommand.class);
        verify(service, times(1)).record(captor.capture());
        assertThat(captor.getValue().statusCode()).isEqualTo(206);
        assertThat(captor.getValue().userId()).isEqualTo(10002L);
        assertThat(captor.getValue().routeTemplate())
                .isEqualTo("/api/ai/conversations/{conversationId}/responses");
    }

    @Test
    void recordsAsyncErrorAsFailureAndIgnoresLaterCompletion() throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletRequest.startAsync(servletRequest, servletResponse));
        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        AsyncListener listener = asyncContext.getListeners().get(0);

        listener.onError(new AsyncEvent(
                asyncContext, new IOException("controlled test failure")));
        listener.onComplete(new AsyncEvent(asyncContext));

        ArgumentCaptor<AccessAuditCommand> captor =
                ArgumentCaptor.forClass(AccessAuditCommand.class);
        verify(service, times(1)).record(captor.capture());
        assertThat(captor.getValue().statusCode()).isEqualTo(500);
    }

    @Test
    void recordsAsyncTimeoutAsFailureAndIgnoresLaterCompletion() throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = asyncRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletRequest.startAsync(servletRequest, servletResponse));
        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        AsyncListener listener = asyncContext.getListeners().get(0);

        listener.onTimeout(new AsyncEvent(asyncContext));
        listener.onComplete(new AsyncEvent(asyncContext));

        ArgumentCaptor<AccessAuditCommand> captor =
                ArgumentCaptor.forClass(AccessAuditCommand.class);
        verify(service, times(1)).record(captor.capture());
        assertThat(captor.getValue().statusCode()).isEqualTo(500);
    }

    @Test
    void clientIpResolutionFailureDoesNotChangeTheBusinessResponse()
            throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("test failure"));
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        verify(service, never()).record(any());
    }

    @Test
    void auditServiceFailureDoesNotChangeTheBusinessResponse() throws Exception {
        AccessAuditEventService service = mock(AccessAuditEventService.class);
        doThrow(new IllegalStateException("test failure"))
                .when(service).record(any());
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        AccessRequestAuditFilter filter = new AccessRequestAuditFilter(service, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        verify(service, times(1)).record(any());
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

    private static MockHttpServletRequest asyncRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/responses");
        request.setAsyncSupported(true);
        return request;
    }
}
