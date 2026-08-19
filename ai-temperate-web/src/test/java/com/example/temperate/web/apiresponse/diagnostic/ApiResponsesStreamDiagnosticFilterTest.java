package com.example.temperate.web.apiresponse.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 该测试是来验证 Responses Servlet 诊断复用既有 Trace ID、观察异步分派且不读取正文、凭据或原始 Accept。
 */
final class ApiResponsesStreamDiagnosticFilterTest {

    @Test
    void committedSseIoIsRecordedAsClientDisconnectWithoutDispatchError()
            throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(properties, System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "trace-responses-disconnect");

        try (LogCapture logs = LogCapture.start()) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                    filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                        servletResponse.setContentType("text/event-stream;charset=UTF-8");
                        servletResponse.flushBuffer();
                        throw new IOException("private-localized-message");
                    }));

            assertThat(logs.joined())
                    .contains("outcome=CLIENT_DISCONNECTED")
                    .contains("failureType=java.io.IOException")
                    .doesNotContain("dispatch_error")
                    .doesNotContain("private-localized-message");
            assertThat(logs.hasLevel(Level.WARN)).isFalse();
            assertThat(logs.hasLevel(Level.ERROR)).isFalse();
        }
    }

    @Test
    void committedNonDisconnectFailureRemainsAnObservableServerError()
            throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(properties, System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "trace-responses-server-error");

        try (LogCapture logs = LogCapture.start()) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                        servletResponse.setContentType("text/event-stream");
                        servletResponse.flushBuffer();
                        throw new IllegalStateException("private-message");
                    }));

            assertThat(logs.joined())
                    .contains("event=api_responses_servlet_dispatch_error")
                    .doesNotContain("private-message");
            assertThat(logs.hasLevel(Level.ERROR)).isTrue();
        }
    }

    @Test
    void recordsFailedRequestAndPreservesExistingTraceAndResponse() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        AtomicLong nanos = new AtomicLong();
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(
                        properties, () -> nanos.addAndGet(1_000_000L));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        request.setContentType("application/json");
        request.addHeader("Accept", "text/event-stream, application/json;q=0.9");
        request.addHeader("Authorization", "Bearer sk-never-log-this");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "trace-existing-responses");

        try (LogCapture logs = LogCapture.start()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                ((HttpServletResponse) servletResponse).setStatus(500);
                servletResponse.setContentType("application/json");
                servletResponse.getWriter().write("unchanged-secret-body");
            });

            assertThat(response.getContentAsString()).isEqualTo("unchanged-secret-body");
            assertThat(response.getHeader("X-Trace-Id"))
                    .isEqualTo("trace-existing-responses");
            assertThat(logs.joined())
                    .contains("event=api_responses_servlet_dispatch")
                    .contains("event=api_responses_servlet_complete")
                    .contains("diagnosticSchema=responses-diag-v1")
                    .contains("traceId=trace-existing-responses")
                    .contains("acceptHeaderClass=SSE_AND_JSON")
                    .contains("responseContentType=application/json")
                    .contains("status=500")
                    .doesNotContain(
                            "sk-never-log-this",
                            "unchanged-secret-body",
                            "q=0.9");
        }
    }

    @Test
    void recordsAsyncCompletionWithTheSameTrace() throws Exception {
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(
                        new ApiKeyProperties(), System::nanoTime);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/responses");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "trace-async-responses");

        try (LogCapture logs = LogCapture.start()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    servletRequest.startAsync());
            ((MockAsyncContext) request.getAsyncContext()).complete();

            assertThat(logs.joined())
                    .contains("event=api_responses_servlet_dispatch")
                    .contains("event=api_responses_servlet_complete")
                    .contains("outcome=ASYNC_COMPLETE")
                    .contains("traceId=trace-async-responses");
        }
    }

    @Test
    void zeroSampleRateKeepsSuccessSilentButAlwaysReportsFailure() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(
                        properties, System::nanoTime);

        try (LogCapture logs = LogCapture.start()) {
            MockHttpServletResponse success = new MockHttpServletResponse();
            success.setHeader("X-Trace-Id", "trace-success");
            filter.doFilter(new MockHttpServletRequest(
                            "POST", "/v1/responses"), success,
                    (servletRequest, servletResponse) ->
                            ((HttpServletResponse) servletResponse).setStatus(200));
            assertThat(logs.joined()).isEmpty();

            MockHttpServletResponse failure = new MockHttpServletResponse();
            failure.setHeader("X-Trace-Id", "trace-failure");
            filter.doFilter(new MockHttpServletRequest(
                            "POST", "/v1/responses"), failure,
                    (servletRequest, servletResponse) ->
                            ((HttpServletResponse) servletResponse).setStatus(503));

            assertThat(logs.joined())
                    .contains("event=api_responses_servlet_dispatch")
                    .contains("event=api_responses_servlet_complete")
                    .contains("traceId=trace-failure")
                    .contains("status=503");
        }
    }

    @Test
    void ignoresWrongMethodTrailingSlashAndOtherV1Paths() throws Exception {
        ApiResponsesStreamDiagnosticFilter filter =
                new ApiResponsesStreamDiagnosticFilter(
                        new ApiKeyProperties(), System::nanoTime);
        String[][] cases = {
            {"GET", "/v1/responses"},
            {"POST", "/v1/responses/"},
            {"POST", "/v1/chat/completions"}
        };

        try (LogCapture logs = LogCapture.start()) {
            for (String[] testCase : cases) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(
                        new MockHttpServletRequest(testCase[0], testCase[1]),
                        response,
                        (servletRequest, servletResponse) ->
                                servletResponse.getWriter().write("unchanged"));
                assertThat(response.getContentAsString()).isEqualTo("unchanged");
            }
            assertThat(logs.joined()).isEmpty();
        }
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender,
            Level previousLevel) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiResponsesStreamDiagnosticFilter.class);
            Level previousLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender, previousLevel);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private boolean hasLevel(Level level) {
            return appender.list.stream().anyMatch(event -> event.getLevel() == level);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }
}
