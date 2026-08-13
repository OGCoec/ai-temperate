package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionGateway;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.security.VoiceHandshakePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 验证握手授权后的公开语音 WebSocket 初始化、逐字节 PCM 转发和最终事件关闭语义。
 */
final class VoiceWebSocketHandlerTest {

    private final VoiceTranscriptionGateway gateway = mock(VoiceTranscriptionGateway.class);
    private final VoiceTranscriptionSession upstream = mock(VoiceTranscriptionSession.class);
    private final AtomicReference<VoiceTranscriptionListener> upstreamListener = new AtomicReference<>();
    private final WebSocketSession client = mock(WebSocketSession.class);
    private final HashMap<String, Object> attributes = new HashMap<>();
    private final VoiceWebSocketHandler handler = new VoiceWebSocketHandler(
            gateway,
            properties(),
            new ObjectMapper());

    @BeforeEach
    void setUp() {
        attributes.put(VoiceHandshakePrincipal.ATTRIBUTE,
                new VoiceHandshakePrincipal(10001L, "AAAAAAAAAAA", "用户",
                        com.example.temperate.service.user.voice.VoiceClientPlatform.H5));
        attributes.put(
                VoiceDiagnosticContext.ATTRIBUTE,
                new VoiceDiagnosticContext("trace-handler", "edge-handler"));
        when(client.getAttributes()).thenReturn(attributes);
        when(client.isOpen()).thenReturn(true);
        when(upstream.sendAudio(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(upstream.sendText(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(upstream.close(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gateway.open(any(VoiceDiagnosticContext.class), anyString(), any()))
                .thenAnswer(invocation -> {
            upstreamListener.set(invocation.getArgument(2));
            return CompletableFuture.completedFuture(upstream);
        });
    }

    @Test
    void forwardsPcmBytesWithoutSendingHandshakeCredentialsUpstream() throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));

        ArgumentCaptor<String> upstreamStart = ArgumentCaptor.forClass(String.class);
        verify(gateway).open(
                any(VoiceDiagnosticContext.class), upstreamStart.capture(), any());
        assertThat(upstreamStart.getValue()).doesNotContain("ticket", "protocolVersion");

        upstreamListener.get().onText("{\"type\":\"session.ready\"}");
        byte[] pcm = new byte[] {0, 1, 2, 3, 4, 5};
        handler.handleMessage(client, new BinaryMessage(pcm));

        ArgumentCaptor<ByteBuffer> audio = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(upstream).sendAudio(audio.capture());
        byte[] forwarded = new byte[audio.getValue().remaining()];
        audio.getValue().get(forwarded);
        assertThat(forwarded).containsExactly(pcm);
    }

    @Test
    void forwardsFinalTextThenClosesBothConnectionsNormally() throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));
        upstreamListener.get().onText("{\"type\":\"session.ready\"}");
        upstreamListener.get().onText(
                "{\"type\":\"transcript.final\",\"sequence\":1,\"text\":\"你好\",\"startMs\":0,\"endMs\":100}");

        ArgumentCaptor<WebSocketMessage<?>> clientMessages =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(client, org.mockito.Mockito.atLeast(2)).sendMessage(clientMessages.capture());
        assertThat(clientMessages.getAllValues().stream()
                .map(message -> String.valueOf(message.getPayload())))
                .anyMatch(payload -> payload.contains("transcript.final") && payload.contains("你好"));
        verify(upstream).close(1000, "TRANSCRIPT_FINAL");
    }

    @Test
    void forwardsIncrementalTranscriptsWithoutLoggingTextOrClosingBeforeFinal()
            throws Exception {
        try (LoggerCapture logs = capture()) {
            handler.afterConnectionEstablished(client);
            handler.handleMessage(client, new TextMessage(startMessage()));
            upstreamListener.get().onText("{\"type\":\"session.ready\"}");
            upstreamListener.get().onText(
                    "{\"type\":\"transcript.partial\",\"sequence\":1,"
                            + "\"text\":\"临时隐私文字\",\"startMs\":0,\"endMs\":800}");
            upstreamListener.get().onText(
                    "{\"type\":\"transcript.partial\",\"sequence\":2,"
                            + "\"text\":\"临时隐私文字更新\",\"startMs\":0,\"endMs\":1600}");

            ArgumentCaptor<WebSocketMessage<?>> partialMessages =
                    ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(client, org.mockito.Mockito.atLeast(3))
                    .sendMessage(partialMessages.capture());
            assertThat(partialMessages.getAllValues().stream()
                    .map(message -> String.valueOf(message.getPayload())))
                    .anyMatch(payload -> payload.contains("transcript.partial")
                            && payload.contains("临时隐私文字更新"));
            verify(upstream, never()).close(anyInt(), anyString());
            verify(client, never()).close(any(org.springframework.web.socket.CloseStatus.class));

            handler.handleMessage(client, new TextMessage("{\"type\":\"input.commit\"}"));
            verify(upstream).sendText("{\"type\":\"input.commit\"}");
            upstreamListener.get().onText(
                    "{\"type\":\"transcript.final\",\"sequence\":3,"
                            + "\"text\":\"最终文字\",\"startMs\":0,\"endMs\":1700}");

            verify(upstream).close(1000, "TRANSCRIPT_FINAL");
            assertThat(logs.messages())
                    .noneMatch(message -> message.contains("临时隐私文字")
                            || message.contains("最终文字"));
        }
    }

    @Test
    void rejectsRepeatedTranscriptSequenceAsAnUpstreamProtocolFailure()
            throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));
        upstreamListener.get().onText("{\"type\":\"session.ready\"}");
        upstreamListener.get().onText(
                "{\"type\":\"transcript.partial\",\"sequence\":1,\"text\":\"甲\"}");
        upstreamListener.get().onText(
                "{\"type\":\"transcript.partial\",\"sequence\":1,\"text\":\"乙\"}");

        ArgumentCaptor<WebSocketMessage<?>> messages =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(client, org.mockito.Mockito.atLeast(3)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().stream()
                .map(message -> String.valueOf(message.getPayload())))
                .anyMatch(payload -> payload.contains("VOICE_PROTOCOL_INVALID"));
        verify(upstream).close(anyInt(), anyString());
    }

    @Test
    void sessionStopIsSerializedUpstreamBeforeBothConnectionsClose() throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));
        upstreamListener.get().onText("{\"type\":\"session.ready\"}");

        handler.handleMessage(client, new TextMessage("{\"type\":\"session.stop\"}"));

        verify(upstream).sendText("{\"type\":\"session.stop\"}");
        verify(upstream).close(1000, "SESSION_STOPPED");
        verify(client).close(any(org.springframework.web.socket.CloseStatus.class));
    }

    @Test
    void forwardsValidatedQueueUpdatesAndAllowsExplicitQueueCancellation() throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));

        upstreamListener.get().onText(queuedEvent(2));
        handler.handleMessage(client, new TextMessage("{\"type\":\"session.stop\"}"));

        ArgumentCaptor<WebSocketMessage<?>> clientMessages =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).sendMessage(clientMessages.capture());
        assertThat(clientMessages.getAllValues().stream()
                .map(message -> String.valueOf(message.getPayload())))
                .anyMatch(payload -> payload.contains("session.queued")
                        && payload.contains("\"position\":2"));
        verify(upstream).sendText("{\"type\":\"session.stop\"}");
    }

    @Test
    void preservesEarlyQueuedAndReadyEventsUntilUpstreamFutureCompletes() throws Exception {
        CompletableFuture<VoiceTranscriptionSession> opening = new CompletableFuture<>();
        when(gateway.open(any(VoiceDiagnosticContext.class), anyString(), any()))
                .thenAnswer(invocation -> {
            upstreamListener.set(invocation.getArgument(2));
            return opening;
        });
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));

        upstreamListener.get().onText(queuedEvent(1));
        upstreamListener.get().onText("{\"type\":\"session.ready\"}");
        opening.complete(upstream);

        ArgumentCaptor<WebSocketMessage<?>> clientMessages =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(client, org.mockito.Mockito.atLeast(2)).sendMessage(clientMessages.capture());
        List<String> payloads = clientMessages.getAllValues().stream()
                .map(message -> String.valueOf(message.getPayload()))
                .toList();
        assertThat(payloads).anyMatch(payload -> payload.contains("session.queued"));
        assertThat(payloads).anyMatch(payload -> payload.contains("session.ready"));
    }

    @Test
    void mapsQueueFullToRetryablePublicError() throws Exception {
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage()));

        upstreamListener.get().onText(
                "{\"type\":\"error\",\"code\":\"VOICE_QUEUE_FULL\","
                        + "\"message\":\"full\",\"retryable\":true}");

        ArgumentCaptor<WebSocketMessage<?>> clientMessages =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).sendMessage(clientMessages.capture());
        assertThat(clientMessages.getAllValues().stream()
                .map(message -> String.valueOf(message.getPayload())))
                .anyMatch(payload -> payload.contains("VOICE_QUEUE_FULL")
                        && payload.contains("\"retryable\":true"));
    }

    @Test
    void logsConnectionStartTransportErrorAndCloseWithoutPayloadOrReason()
            throws Exception {
        LoggerCapture capture = capture();
        try {
            handler.afterConnectionEstablished(client);
            handler.handleMessage(client, new TextMessage(startMessage()));
            handler.handleTransportError(
                    client,
                    new IOException("sensitive-transport-message"));
            handler.afterConnectionClosed(
                    client,
                    new org.springframework.web.socket.CloseStatus(
                            1006, "sensitive-close-reason"));

            assertThat(capture.messages()).anySatisfy(message ->
                    assertThat(message).contains(
                            "event=voice_ws_connection_lifecycle",
                            "traceId=trace-handler",
                            "edgeRay=edge-handler",
                            "phase=CONNECTION_ESTABLISHED"));
            assertThat(capture.messages()).anySatisfy(message ->
                    assertThat(message).contains("phase=SESSION_START_ACCEPTED"));
            assertThat(capture.messages()).anySatisfy(message ->
                    assertThat(message).contains(
                            "phase=TRANSPORT_ERROR",
                            "exceptionType=IOException"));
            assertThat(capture.messages()).anySatisfy(message ->
                    assertThat(message).contains(
                            "phase=CONNECTION_CLOSED",
                            "closeCode=1006"));
            assertThat(capture.messages()).allSatisfy(message ->
                    assertThat(message).doesNotContain(
                            "sensitive-transport-message",
                            "sensitive-close-reason",
                            "session.start",
                            "pcm_s16le"));
        } finally {
            capture.close();
        }
    }

    @Test
    void rejectsMissingDiagnosticContextWithoutCreatingConnectionState() {
        attributes.remove(VoiceDiagnosticContext.ATTRIBUTE);
        LoggerCapture capture = capture();
        try {
            assertThatThrownBy(() -> handler.afterConnectionEstablished(client))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(capture.messages()).singleElement().satisfies(message ->
                    assertThat(message).contains(
                            "phase=CONNECTION_CONTEXT_MISSING",
                            "exceptionType=IllegalStateException"));
        } finally {
            capture.close();
        }
    }

    private static String startMessage() {
        return "{\"type\":\"session.start\",\"protocolVersion\":2,"
                + "\"language\":\"auto\","
                + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,\"channels\":1}";
    }

    private static String queuedEvent(int position) {
        return "{\"type\":\"session.queued\","
                + "\"sessionId\":\"0123456789abcdef0123456789abcdef\","
                + "\"position\":" + position
                + ",\"queueCapacity\":5,\"maxWaitMs\":90000}";
    }

    private static VoiceProperties properties() {
        return new VoiceProperties(
                true,
                "/ws/voice",
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                10,
                Duration.ofMinutes(5),
                Duration.ofMillis(800),
                3,
                5,
                Duration.ofSeconds(90),
                List.of("https://localhost:3000"),
                URI.create("wss://127.0.0.1:7896/ws/transcribe"),
                "file:test.pem",
                Duration.ofSeconds(5),
                Duration.ofMinutes(2));
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(VoiceWebSocketHandler.class);
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
