package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseService;
import com.example.temperate.web.admin.security.AdminSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 验证邮件检查 SSE 入口的无缓存、禁缓冲、trace 与 Last-Event-ID 转发契约。
 */
final class AdminMailInspectionSseControllerTest {

    @Test
    void returnsStreamingHeadersAndPassesRevisionWithoutTokenInUrl() {
        CapturingSseService service = new CapturingSseService();
        AdminMailInspectionSseController controller =
                new AdminMailInspectionSseController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                AuthRequestTraceFilter.TRACE_ATTRIBUTE,
                "trace-test");
        request.setAttribute(
                AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE,
                "session-secret");

        var response = controller.events(
                new MailInspectionJobPublicId(
                        "AZ9nEjRWeJCrze8SNFZ4kA"),
                "17",
                request);

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store, private, no-transform");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering"))
                .isEqualTo("no");
        assertThat(response.getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo("trace-test");
        assertThat(service.jobId)
                .isEqualTo("AZ9nEjRWeJCrze8SNFZ4kA");
        assertThat(service.lastEventId).isEqualTo("17");
        assertThat(service.sessionKey)
                .doesNotContain("session-secret")
                .hasSize(43);
    }

    private static final class CapturingSseService
            implements MailInspectionSseService {

        private String jobId;
        private String lastEventId;
        private String sessionKey;

        @Override
        public SseEmitter connect(
                String jobId,
                String lastEventId,
                String sessionKey) {
            this.jobId = jobId;
            this.lastEventId = lastEventId;
            this.sessionKey = sessionKey;
            return new SseEmitter();
        }
    }
}
