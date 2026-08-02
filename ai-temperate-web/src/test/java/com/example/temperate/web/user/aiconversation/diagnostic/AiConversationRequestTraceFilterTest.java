package com.example.temperate.web.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 AI 会话响应请求获得服务端 Trace ID，并安全接收可选的客户端关联 ID。
 */
final class AiConversationRequestTraceFilterTest {

    @Test
    void tracesCreateResponseRequestAndRestoresMdc() throws Exception {
        AiConversationRequestTraceFilter filter =
                new AiConversationRequestTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/ai/conversations/responses");
        String clientRequestId = UUID.randomUUID().toString();
        request.addHeader(
                AiConversationRequestTraceFilter.CLIENT_REQUEST_HEADER,
                clientRequestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(AiConversationRequestTraceFilter.TRACE_MDC_KEY))
                    .isNotBlank();
            assertThat(MDC.get(
                    AiConversationRequestTraceFilter.CLIENT_REQUEST_MDC_KEY))
                    .isEqualTo(clientRequestId);
            assertThat(Long.parseLong(MDC.get(
                    AiConversationRequestTraceFilter.STARTED_NANOS_MDC_KEY)))
                    .isPositive();
        });

        assertThat(response.getHeader(AiConversationRequestTraceFilter.TRACE_HEADER))
                .satisfies(value -> UUID.fromString(value));
        assertThat(request.getAttribute(
                AiConversationRequestTraceFilter.TRACE_ATTRIBUTE)).isNotNull();
        assertThat(MDC.get(AiConversationRequestTraceFilter.TRACE_MDC_KEY)).isNull();
        assertThat(MDC.get(
                AiConversationRequestTraceFilter.CLIENT_REQUEST_MDC_KEY)).isNull();
        assertThat(MDC.get(
                AiConversationRequestTraceFilter.STARTED_NANOS_MDC_KEY)).isNull();
    }

    @Test
    void ignoresMalformedClientCorrelationWithoutRejectingBusinessRequest()
            throws Exception {
        AiConversationRequestTraceFilter filter =
                new AiConversationRequestTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/ai/conversations/example/responses");
        request.addHeader(
                AiConversationRequestTraceFilter.CLIENT_REQUEST_HEADER,
                "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(
                AiConversationRequestTraceFilter.CLIENT_REQUEST_ATTRIBUTE))
                .isEqualTo("unavailable");
    }

    @Test
    void leavesUnrelatedAiRequestOutsideBoundary() throws Exception {
        AiConversationRequestTraceFilter filter =
                new AiConversationRequestTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/ai/models");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(AiConversationRequestTraceFilter.TRACE_HEADER))
                .isNull();
    }
}
