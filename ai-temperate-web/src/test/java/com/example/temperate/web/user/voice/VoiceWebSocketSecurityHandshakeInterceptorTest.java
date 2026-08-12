package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.security.VoiceHandshakePrincipal;
import com.example.temperate.service.user.voice.security.VoiceWebSocketAuthorizationService;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContext;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * 验证 Voice Ticket 在返回 101 前完成授权，且成功属性不会保留握手凭据。
 */
final class VoiceWebSocketSecurityHandshakeInterceptorTest {

    @Test
    void authorizesBeforeHandshakeAndKeepsOnlyPrincipalAndDiagnosticContext() {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        VoiceHandshakePrincipal principal = new VoiceHandshakePrincipal(
                10001L, "AAAAAAAAAAA", "用户", VoiceClientPlatform.H5);
        when(service.authorize(any())).thenReturn(principal);
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());

        MockHttpServletRequest request = request();
        HashMap<String, Object> attributes = attributes();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsOnlyKeys(
                VoiceHandshakePrincipal.ATTRIBUTE,
                VoiceDiagnosticContext.ATTRIBUTE);
        assertThat(attributes.get(VoiceHandshakePrincipal.ATTRIBUTE)).isSameAs(principal);
        assertThat(attributes.get(VoiceDiagnosticContext.ATTRIBUTE))
                .isInstanceOf(VoiceDiagnosticContext.class);
    }

    @Test
    void rejectsMalformedProtocolWithoutConsumingTicket() {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());
        MockHttpServletRequest request = request();
        request.removeHeader("Sec-WebSocket-Protocol");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMultipleProtocolHeaderLinesWithoutConsumingTicket() {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());
        MockHttpServletRequest request = request();
        request.addHeader("Sec-WebSocket-Protocol",
                "ait-voice-v2, ait-ticket." + "B".repeat(43));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void mapsEverySecurityFailureBeforeReturningUpgradeResponse() {
        assertRejected(VoiceErrorCode.VOICE_TICKET_INVALID, 401);
        assertRejected(VoiceErrorCode.VOICE_SESSION_INVALID, 401);
        assertRejected(VoiceErrorCode.VOICE_DEVICE_BLOCKED, 403);
        assertRejected(VoiceErrorCode.VOICE_PREAUTH_REQUIRED, 428);
        assertRejected(VoiceErrorCode.VOICE_WEBRTC_REQUIRED, 428);
        assertRejected(VoiceErrorCode.VOICE_INFRASTRUCTURE_UNAVAILABLE, 503);
    }

    @Test
    void logsStableAuthorizationCodeWithoutTicketIpOrExceptionMessage() {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        when(service.authorize(any())).thenThrow(new VoiceException(
                VoiceErrorCode.VOICE_SESSION_INVALID,
                "sensitive-authorization-message",
                false));
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());
        LoggerCapture capture = capture();
        try {
            boolean accepted = interceptor.beforeHandshake(
                    new ServletServerHttpRequest(request()),
                    new ServletServerHttpResponse(new MockHttpServletResponse()),
                    mock(WebSocketHandler.class),
                    attributes());

            assertThat(accepted).isFalse();
            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "event=voice_ws_authorization",
                        "traceId=trace-security",
                        "edgeRay=edge-security",
                        "protocolShapeValid=true",
                        "networkContextPresent=true",
                        "platform=H5",
                        "originPresent=true",
                        "authorized=false",
                        "errorCode=VOICE_SESSION_INVALID",
                        "status=401",
                        "exceptionType=VoiceException");
                assertThat(message).doesNotContain(
                        "sensitive-authorization-message",
                        "203.0.113.10",
                        "ait-ticket.",
                        "A".repeat(43));
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void logsStableCodesForMissingOriginContextMissingNetworkAndRuntimeFailure() {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());
        LoggerCapture capture = capture();
        try {
            MockHttpServletResponse missingOriginResponse = new MockHttpServletResponse();
            boolean missingOriginAccepted = interceptor.beforeHandshake(
                    new ServletServerHttpRequest(request()),
                    new ServletServerHttpResponse(missingOriginResponse),
                    mock(WebSocketHandler.class),
                    new HashMap<>());

            MockHttpServletRequest missingNetworkRequest = request();
            missingNetworkRequest.removeAttribute(
                    TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE);
            MockHttpServletResponse missingNetworkResponse = new MockHttpServletResponse();
            boolean missingNetworkAccepted = interceptor.beforeHandshake(
                    new ServletServerHttpRequest(missingNetworkRequest),
                    new ServletServerHttpResponse(missingNetworkResponse),
                    mock(WebSocketHandler.class),
                    attributes());

            when(service.authorize(any())).thenThrow(
                    new IllegalStateException("sensitive-runtime-message"));
            MockHttpServletResponse runtimeResponse = new MockHttpServletResponse();
            boolean runtimeAccepted = interceptor.beforeHandshake(
                    new ServletServerHttpRequest(request()),
                    new ServletServerHttpResponse(runtimeResponse),
                    mock(WebSocketHandler.class),
                    attributes());

            assertThat(missingOriginAccepted).isFalse();
            assertThat(missingOriginResponse.getStatus()).isEqualTo(403);
            assertThat(missingNetworkAccepted).isFalse();
            assertThat(missingNetworkResponse.getStatus()).isEqualTo(503);
            assertThat(runtimeAccepted).isFalse();
            assertThat(runtimeResponse.getStatus()).isEqualTo(503);
            assertThat(capture.messages())
                    .anyMatch(message -> message.contains(
                            "errorCode=VOICE_ORIGIN_CONTEXT_INVALID"))
                    .anyMatch(message -> message.contains(
                            "errorCode=VOICE_EDGE_NETWORK_CONTEXT_MISSING"))
                    .anyMatch(message -> message.contains(
                            "errorCode=VOICE_INFRASTRUCTURE_UNAVAILABLE")
                            && message.contains(
                                    "exceptionType=IllegalStateException"))
                    .allSatisfy(message -> assertThat(message).doesNotContain(
                            "sensitive-runtime-message",
                            "203.0.113.10",
                            "ait-ticket."));
        } finally {
            capture.close();
        }
    }

    private static void assertRejected(VoiceErrorCode code, int expectedStatus) {
        VoiceWebSocketAuthorizationService service = mock(
                VoiceWebSocketAuthorizationService.class);
        when(service.authorize(any())).thenThrow(new VoiceException(
                code, "Voice WebSocket authorization failed.", false));
        VoiceWebSocketSecurityHandshakeInterceptor interceptor =
                new VoiceWebSocketSecurityHandshakeInterceptor(
                        service, new TrustedEdgeNetworkContextResolver());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request()),
                serverResponse,
                mock(WebSocketHandler.class),
                attributes());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(serverResponse.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("Sec-WebSocket-Protocol",
                "ait-voice-v2, ait-ticket." + "A".repeat(43));
        request.setAttribute(
                TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE,
                new TrustedEdgeNetworkContext(
                        "203.0.113.10", "US", 64500L, null, null, "test-ray"));
        request.setAttribute(
                VoiceDiagnosticContext.ATTRIBUTE,
                new VoiceDiagnosticContext("trace-security", "edge-security"));
        return request;
    }

    private static HashMap<String, Object> attributes() {
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(VoiceWebSocketOriginInterceptor.PLATFORM_ATTRIBUTE,
                VoiceClientPlatform.H5);
        attributes.put(VoiceWebSocketOriginInterceptor.ORIGIN_PRESENT_ATTRIBUTE,
                Boolean.TRUE);
        return attributes;
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                VoiceWebSocketSecurityHandshakeInterceptor.class);
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
