package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionGateway;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 验证公开语音 WebSocket 的一次性认证、逐字节 PCM 转发和最终事件关闭语义。
 */
final class VoiceWebSocketHandlerTest {

    private final VoiceSessionTicketService ticketService = mock(VoiceSessionTicketService.class);
    private final VoiceTranscriptionGateway gateway = mock(VoiceTranscriptionGateway.class);
    private final VoiceTranscriptionSession upstream = mock(VoiceTranscriptionSession.class);
    private final AtomicReference<VoiceTranscriptionListener> upstreamListener = new AtomicReference<>();
    private final WebSocketSession client = mock(WebSocketSession.class);
    private final HashMap<String, Object> attributes = new HashMap<>();
    private final VoiceWebSocketHandler handler = new VoiceWebSocketHandler(
            ticketService,
            gateway,
            properties(),
            new ObjectMapper());

    @BeforeEach
    void setUp() {
        attributes.put(
                VoiceWebSocketOriginInterceptor.ORIGIN_PRESENT_ATTRIBUTE,
                Boolean.TRUE);
        when(client.getAttributes()).thenReturn(attributes);
        when(client.isOpen()).thenReturn(true);
        when(upstream.sendAudio(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(upstream.sendText(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(upstream.close(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(ticketService.consume(anyString())).thenReturn(new VoiceSessionTicketSnapshot(
                1,
                10001L,
                VoiceClientPlatform.H5,
                "550e8400-e29b-41d4-a716-446655440000",
                Instant.parse("2026-08-07T12:00:30Z")));
        when(gateway.open(anyString(), any())).thenAnswer(invocation -> {
            upstreamListener.set(invocation.getArgument(1));
            return CompletableFuture.completedFuture(upstream);
        });
    }

    @Test
    void consumesTicketAndForwardsPcmBytesWithoutIncludingTicketUpstream() throws Exception {
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));

        ArgumentCaptor<String> upstreamStart = ArgumentCaptor.forClass(String.class);
        verify(gateway).open(upstreamStart.capture(), any());
        assertThat(upstreamStart.getValue()).doesNotContain(ticket, "protocolVersion");

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
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));
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
    void sessionStopIsSerializedUpstreamBeforeBothConnectionsClose() throws Exception {
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));
        upstreamListener.get().onText("{\"type\":\"session.ready\"}");

        handler.handleMessage(client, new TextMessage("{\"type\":\"session.stop\"}"));

        verify(upstream).sendText("{\"type\":\"session.stop\"}");
        verify(upstream).close(1000, "SESSION_STOPPED");
        verify(client).close(any(org.springframework.web.socket.CloseStatus.class));
    }

    @Test
    void forwardsValidatedQueueUpdatesAndAllowsExplicitQueueCancellation() throws Exception {
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));

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
        when(gateway.open(anyString(), any())).thenAnswer(invocation -> {
            upstreamListener.set(invocation.getArgument(1));
            return opening;
        });
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));

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
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        handler.afterConnectionEstablished(client);
        handler.handleMessage(client, new TextMessage(startMessage(ticket)));

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

    private static String startMessage(String ticket) {
        return "{\"type\":\"session.start\",\"protocolVersion\":1,"
                + "\"ticket\":\"" + ticket + "\",\"language\":\"auto\","
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
                Duration.ofMillis(1500),
                3,
                5,
                Duration.ofSeconds(90),
                List.of("https://localhost:3000"),
                URI.create("wss://127.0.0.1:7896/ws/transcribe"),
                "file:test.pem",
                Duration.ofSeconds(5),
                Duration.ofMinutes(2));
    }
}
