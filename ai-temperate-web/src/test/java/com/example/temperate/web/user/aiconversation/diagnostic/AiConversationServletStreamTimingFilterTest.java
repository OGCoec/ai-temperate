package com.example.temperate.web.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 该测试是来验证 Servlet 流式诊断只观察实际 write/flush，不缓存或改变 SSE 响应内容。
 */
final class AiConversationServletStreamTimingFilterTest {

    @Test
    void committedSseIoIsRecordedAsClientDisconnectInsteadOfServerError()
            throws Exception {
        CapturingTransportDiagnostics diagnostics = new CapturingTransportDiagnostics();
        AiConversationServletStreamTimingFilter filter =
                new AiConversationServletStreamTimingFilter(diagnostics, () -> 100L);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/ai/conversations/responses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                    servletResponse.setContentType("text/event-stream");
                    servletResponse.getOutputStream().write("data: tail\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    servletResponse.flushBuffer();
                    throw new IOException("private-localized-message");
                }));

        assertThat(diagnostics.records)
                .filteredOn(record -> "ai_stream_servlet_complete".equals(record.event()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.details().get("eventType"))
                            .isEqualTo("CLIENT_DISCONNECTED");
                    assertThat(record.details().get("failureType"))
                            .isEqualTo(IOException.class.getName());
                });
    }

    @Test
    void recordsFlushedSseBytesWithoutChangingResponseBody() throws Exception {
        CapturingTransportDiagnostics diagnostics = new CapturingTransportDiagnostics();
        AiConversationServletStreamTimingFilter filter =
                new AiConversationServletStreamTimingFilter(
                        diagnostics, () -> 100L);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/ai/conversations/generations/AZ-vpV3kfag70-0EMMUETQ/events");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletResponse.getOutputStream().write(
                    "event: delta\ndata: {\"revision\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            servletResponse.flushBuffer();
        });

        assertThat(response.getContentAsString())
                .isEqualTo("event: delta\ndata: {\"revision\":1}\n\n");
        assertThat(diagnostics.records)
                .anySatisfy(record -> {
                    assertThat(record.event()).isEqualTo("ai_stream_servlet_write");
                    assertThat(record.details().get("firstWrite")).isEqualTo(true);
                    assertThat(record.details().get("eventType")).isEqualTo("delta");
                    assertThat(record.details().get("revision")).isEqualTo(1L);
                });
    }

    private static final class CapturingTransportDiagnostics
            implements AiConversationStreamTransportDiagnosticService {
        private final List<Record> records = new ArrayList<>();

        @Override
        public void record(
                AiConversationStreamTimingContext context,
                String event,
                Map<String, ?> details) {
            assertThat(context.path()).isEqualTo(
                    AiConversationStreamTimingPath.SERVLET_SSE_RESPONSE);
            records.add(new Record(event, Map.copyOf(details)));
        }
    }

    private record Record(String event, Map<String, ?> details) {
    }
}
