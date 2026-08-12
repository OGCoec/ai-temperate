package com.example.temperate.web.user.voice.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证语音 WebSocket 诊断过滤器只观察握手边界，不修改响应或泄露认证材料。
 */
final class VoiceWebSocketDiagnosticFilterTest {

    @Test
    void logsSuccessfulUpgradeWithoutChangingResponseOrSensitiveHeaders()
            throws Exception {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = voiceRequest();
        String cookie = "access_token=do-not-log; XSRF-TOKEN=do-not-log";
        String ticket = "A".repeat(43);
        request.addHeader("Cookie", cookie);
        request.addHeader(
                "Sec-WebSocket-Protocol",
                "ait-voice-v2, ait-ticket." + ticket);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInsideChain = new AtomicReference<>();
        AtomicReference<String> edgeRayInsideChain = new AtomicReference<>();
        LoggerCapture capture = capture();
        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                traceInsideChain.set(MDC.get(VoiceWebSocketDiagnosticFilter.TRACE_MDC_KEY));
                edgeRayInsideChain.set(MDC.get(VoiceWebSocketDiagnosticFilter.EDGE_RAY_MDC_KEY));
                jakarta.servlet.http.HttpServletResponse httpResponse =
                        (jakarta.servlet.http.HttpServletResponse) servletResponse;
                httpResponse.setStatus(101);
                httpResponse.setHeader("Sec-WebSocket-Protocol", "ait-voice-v2");
            });

            assertThat(traceInsideChain.get()).isNotBlank();
            assertThat(edgeRayInsideChain.get()).isEqualTo("test-ray-ord");
            assertThat(request.getAttribute(VoiceDiagnosticContext.ATTRIBUTE))
                    .isInstanceOf(VoiceDiagnosticContext.class);
            assertThat(response.getStatus()).isEqualTo(101);
            assertThat(response.getHeader("Sec-WebSocket-Protocol"))
                    .isEqualTo("ait-voice-v2");
            assertThat(response.getHeader("X-Trace-Id")).isNull();
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(MDC.get(VoiceWebSocketDiagnosticFilter.TRACE_MDC_KEY)).isNull();
            assertThat(MDC.get(VoiceWebSocketDiagnosticFilter.EDGE_RAY_MDC_KEY)).isNull();

            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "event=voice_ws_handshake_summary",
                        "edgeRay=test-ray-ord",
                        "platform=H5",
                        "cookieHeaderPresent=true",
                        "cookieHeaderBytes="
                                + cookie.getBytes(StandardCharsets.UTF_8).length,
                        "protocolHeaderPresent=true",
                        "protocolTokenCount=2",
                        "voiceV2Present=true",
                        "status=101",
                        "selectedProtocolMatched=true",
                        "setCookiePresent=false",
                        "outcome=UPGRADED",
                        "exceptionType=ABSENT");
                assertThat(message).doesNotContain(
                        "do-not-log",
                        ticket,
                        "ait-ticket.",
                        "access_token",
                        "XSRF-TOKEN");
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void skipsEveryPathExceptTheExactVoiceWebSocketPath() throws Exception {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice/other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoggerCapture capture = capture();
        try {
            filter.doFilter(request, response, new MockFilterChain());

            assertThat(capture.messages()).isEmpty();
            assertThat(request.getAttribute(VoiceDiagnosticContext.ATTRIBUTE)).isNull();
            assertThat(response.getHeaderNames()).isEmpty();
        } finally {
            capture.close();
        }
    }

    @Test
    void recordsRejectedResponseWithoutChangingStatusOrSetCookie() throws Exception {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = voiceRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoggerCapture capture = capture();
        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                jakarta.servlet.http.HttpServletResponse httpResponse =
                        (jakarta.servlet.http.HttpServletResponse) servletResponse;
                httpResponse.setStatus(403);
                httpResponse.addHeader(
                        "Set-Cookie",
                        "diagnostic-test=do-not-log; Secure; HttpOnly");
            });

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "status=403",
                        "setCookiePresent=true",
                        "outcome=REJECTED");
                assertThat(message).doesNotContain("diagnostic-test", "do-not-log");
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void recordsInternalFailureStatusWithoutChangingTheResponse() throws Exception {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = voiceRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        LoggerCapture capture = capture();
        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    ((jakarta.servlet.http.HttpServletResponse) servletResponse)
                            .setStatus(500));

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(capture.messages()).singleElement().satisfies(message ->
                    assertThat(message).contains("status=500", "outcome=REJECTED"));
        } finally {
            capture.close();
        }
    }

    @Test
    void recordsExceptionTypeAndRethrowsTheOriginalException() {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = voiceRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException failure = new IOException("sensitive-exception-message");
        LoggerCapture capture = capture();
        try {
            assertThatThrownBy(() -> filter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) -> {
                        throw failure;
                    })).isSameAs(failure);

            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "outcome=EXCEPTION",
                        "exceptionType=IOException");
                assertThat(message).doesNotContain("sensitive-exception-message");
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void restoresExistingMdcValuesAfterCompletion() throws Exception {
        VoiceWebSocketDiagnosticFilter filter = new VoiceWebSocketDiagnosticFilter();
        MockHttpServletRequest request = voiceRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(VoiceWebSocketDiagnosticFilter.TRACE_MDC_KEY, "previous-trace");
        MDC.put(VoiceWebSocketDiagnosticFilter.EDGE_RAY_MDC_KEY, "previous-ray");
        LoggerCapture capture = capture();
        try {
            filter.doFilter(request, response, new MockFilterChain());

            assertThat(MDC.get(VoiceWebSocketDiagnosticFilter.TRACE_MDC_KEY))
                    .isEqualTo("previous-trace");
            assertThat(MDC.get(VoiceWebSocketDiagnosticFilter.EDGE_RAY_MDC_KEY))
                    .isEqualTo("previous-ray");
        } finally {
            MDC.remove(VoiceWebSocketDiagnosticFilter.TRACE_MDC_KEY);
            MDC.remove(VoiceWebSocketDiagnosticFilter.EDGE_RAY_MDC_KEY);
            capture.close();
        }
    }

    private static MockHttpServletRequest voiceRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("X-AIT-Edge-Ray", "test-ray-ord");
        return request;
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(VoiceWebSocketDiagnosticFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private record LoggerCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private java.util.List<String> messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
