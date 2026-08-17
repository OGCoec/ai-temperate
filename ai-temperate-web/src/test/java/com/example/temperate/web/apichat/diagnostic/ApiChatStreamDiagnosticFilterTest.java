package com.example.temperate.web.apichat.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockAsyncContext;

/**
 * 该测试是来验证公开 API Chat Servlet 诊断只增加 Trace 和生命周期日志，不读取请求正文或改变响应内容。
 */
final class ApiChatStreamDiagnosticFilterTest {

    @Test
    void recordsRequestCompletionAndPreservesResponse() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        AtomicLong nanos = new AtomicLong();
        ApiChatStreamDiagnosticFilter filter = new ApiChatStreamDiagnosticFilter(
                properties, () -> nanos.addAndGet(1_000_000L));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        request.setContentType("application/json");
        request.addHeader("Accept", "text/event-stream, application/json;q=0.9");
        request.addHeader("Authorization", "Bearer sk-never-log-this");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                ((HttpServletResponse) servletResponse).setStatus(500);
                servletResponse.setContentType("application/json");
                servletResponse.getWriter().write("unchanged");
            });

            assertThat(response.getContentAsString()).isEqualTo("unchanged");
            assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
            assertThat(logs.joined()).contains("event=api_chat_servlet_dispatch");
            assertThat(logs.joined()).contains("event=api_chat_servlet_complete");
            assertThat(logs.joined()).contains("acceptsSse=true");
            assertThat(logs.joined()).contains("acceptsJson=true");
            assertThat(logs.joined()).contains("acceptHeaderClass=SSE_AND_JSON");
            assertThat(logs.joined()).contains("responseContentType=application/json");
            assertThat(logs.joined()).contains("status=500");
            assertThat(logs.joined()).doesNotContain("sk-never-log-this");
        }
    }

    @Test
    void classifiesMixedSseJsonWildcardAndAbsentAcceptWithoutLoggingRawHeader()
            throws Exception {
        Map<String, String> cases = Map.of(
                "text/event-stream", "SSE_ONLY",
                "application/json", "JSON_ONLY",
                "text/event-stream, application/json;q=0.9", "SSE_AND_JSON",
                "*/*", "WILDCARD");

        try (LogCapture logs = LogCapture.start()) {
            for (Map.Entry<String, String> testCase : cases.entrySet()) {
                logs.clear();
                MockHttpServletRequest request = new MockHttpServletRequest(
                        "POST", "/v1/chat/completions");
                request.addHeader("Accept", testCase.getKey());
                new ApiChatStreamDiagnosticFilter(
                        new ApiKeyProperties(), System::nanoTime)
                        .doFilter(request, new MockHttpServletResponse(),
                                (servletRequest, servletResponse) -> { });
                assertThat(logs.joined())
                        .contains("acceptHeaderClass=" + testCase.getValue())
                        .doesNotContain("q=0.9");
            }

            logs.clear();
            new ApiChatStreamDiagnosticFilter(
                    new ApiKeyProperties(), System::nanoTime)
                    .doFilter(new MockHttpServletRequest(
                                    "POST", "/v1/chat/completions"),
                            new MockHttpServletResponse(),
                            (servletRequest, servletResponse) -> { });
            assertThat(logs.joined()).contains("acceptHeaderClass=ABSENT");
        }
    }

    @Test
    void disabledDiagnosticsPreservesResponseWithoutTraceOrLogs() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(false);
        ApiChatStreamDiagnosticFilter filter = new ApiChatStreamDiagnosticFilter(
                properties, System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                ((HttpServletResponse) servletResponse).setStatus(418);
                servletResponse.getWriter().write("unchanged-disabled");
            });

            assertThat(response.getStatus()).isEqualTo(418);
            assertThat(response.getContentAsString()).isEqualTo("unchanged-disabled");
            assertThat(response.getHeader("X-Trace-Id")).isNull();
            assertThat(logs.joined()).isEmpty();
        }
    }

    @Test
    void zeroSampleRateSuppressesSuccessfulServletLogsButRecordsFailures() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiChatStreamDiagnosticFilter filter = new ApiChatStreamDiagnosticFilter(
                properties, System::nanoTime);

        try (LogCapture logs = LogCapture.start()) {
            MockHttpServletResponse success = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(
                            "POST", "/v1/chat/completions"), success,
                    (servletRequest, servletResponse) ->
                            ((HttpServletResponse) servletResponse).setStatus(200));
            assertThat(logs.joined()).isEmpty();

            MockHttpServletRequest failedRequest = new MockHttpServletRequest(
                    "POST", "/v1/chat/completions");
            failedRequest.addHeader("Accept",
                    "text/event-stream, application/json;q=0.9");
            MockHttpServletResponse failure = new MockHttpServletResponse();
            filter.doFilter(failedRequest, failure,
                    (servletRequest, servletResponse) -> {
                        ((HttpServletResponse) servletResponse).setStatus(500);
                        servletResponse.setContentType("application/json");
                    });

            assertThat(logs.joined())
                    .contains("event=api_chat_servlet_dispatch")
                    .contains("event=api_chat_servlet_complete")
                    .contains("status=500")
                    .contains("responseContentType=application/json");
        }
    }

    @Test
    void ignoresUnrelatedRoutes() throws Exception {
        ApiChatStreamDiagnosticFilter filter = new ApiChatStreamDiagnosticFilter(
                new ApiKeyProperties(), System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletResponse.getWriter().write("health"));

        assertThat(response.getContentAsString()).isEqualTo("health");
        assertThat(response.getHeader("X-Trace-Id")).isNull();
    }

    @Test
    void recordsAsyncCompletionWithoutReadingStreamBody() throws Exception {
        ApiChatStreamDiagnosticFilter filter = new ApiChatStreamDiagnosticFilter(
                new ApiKeyProperties(), System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (LogCapture logs = LogCapture.start()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    servletRequest.startAsync());
            ((MockAsyncContext) request.getAsyncContext()).complete();

            assertThat(logs.joined()).contains("event=api_chat_servlet_async_started");
            assertThat(logs.joined()).contains("outcome=ASYNC_COMPLETE");
            assertThat(logs.joined()).doesNotContain("Authorization");
        }
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiChatStreamDiagnosticFilter.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private void clear() {
            appender.list.clear();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
