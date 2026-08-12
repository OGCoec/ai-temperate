package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * 验证 H5 与 Android 在 Ticket 消费前必须满足不同且互斥的 Origin/平台运输规则。
 */
final class VoiceWebSocketOriginInterceptorTest {

    @Test
    void acceptsWhitelistedH5AndOriginlessAndroid() {
        VoiceWebSocketOriginInterceptor interceptor = new VoiceWebSocketOriginInterceptor(
                properties());

        HashMap<String, Object> h5 = new HashMap<>();
        assertThat(interceptor.beforeHandshake(
                request("H5", "https://localhost:3000"),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                h5)).isTrue();
        assertThat(h5.get(VoiceWebSocketOriginInterceptor.PLATFORM_ATTRIBUTE))
                .isEqualTo(VoiceClientPlatform.H5);

        HashMap<String, Object> android = new HashMap<>();
        assertThat(interceptor.beforeHandshake(
                request("ANDROID", null),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                android)).isTrue();
        assertThat(android.get(VoiceWebSocketOriginInterceptor.PLATFORM_ATTRIBUTE))
                .isEqualTo(VoiceClientPlatform.ANDROID);
    }

    @Test
    void rejectsPlatformAndOriginMismatch() {
        VoiceWebSocketOriginInterceptor interceptor = new VoiceWebSocketOriginInterceptor(
                properties());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        assertThat(interceptor.beforeHandshake(
                request("ANDROID", "https://localhost:3000"),
                response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void logsOnlyOriginClassificationAndDiagnosticIdentifiers() {
        VoiceWebSocketOriginInterceptor interceptor = new VoiceWebSocketOriginInterceptor(
                properties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Origin", "https://sensitive-origin.example");
        request.setAttribute(
                VoiceDiagnosticContext.ATTRIBUTE,
                new VoiceDiagnosticContext("trace-origin", "edge-origin"));
        LoggerCapture capture = capture();
        try {
            boolean accepted = interceptor.beforeHandshake(
                    new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(new MockHttpServletResponse()),
                    mock(WebSocketHandler.class),
                    new HashMap<>());

            assertThat(accepted).isFalse();
            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "event=voice_ws_origin_classification",
                        "traceId=trace-origin",
                        "edgeRay=edge-origin",
                        "platform=ANDROID",
                        "originPresent=true",
                        "allowed=false",
                        "status=403",
                        "outcome=REJECTED");
                assertThat(message).doesNotContain("sensitive-origin.example");
            });
        } finally {
            capture.close();
        }
    }

    private static ServerHttpRequest request(String platform, String origin) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Platform", platform);
        if (origin != null) {
            headers.setOrigin(origin);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private static VoiceProperties properties() {
        return new VoiceProperties(
                true, "/ws/voice", Duration.ofSeconds(30), Duration.ofMinutes(1),
                10, Duration.ofMinutes(5), Duration.ofMillis(1500), 3, 5,
                Duration.ofSeconds(90), List.of("https://localhost:3000"),
                URI.create("wss://127.0.0.1:7896/ws/transcribe"), "file:test.pem",
                Duration.ofSeconds(5), Duration.ofMinutes(2));
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                VoiceWebSocketOriginInterceptor.class);
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
