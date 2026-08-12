package com.example.temperate.service.user.voice.gateway.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * 使用显式测试开关验证 Java 21 WSS 客户端能够通过指定 PEM 证书连接本机 Whisper。
 *
 * <p>默认测试套件不会访问运行中的服务；第二阶段联调必须同时提供回环 URI 和本地证书路径。</p>
 */
final class JdkWhisperUpstreamClientIntegrationTest {

    @Test
    void recordsConnectFailureOnceAcrossFutureAndListenerCallbacks() {
        URI uri = URI.create("wss://127.0.0.1:7896/ws");
        WebSocketHarness harness = webSocketHarness(uri);
        VoiceTranscriptionListener listener = mock(VoiceTranscriptionListener.class);
        VoiceDiagnosticContext context = new VoiceDiagnosticContext(
                "trace-connect-failed", "edge-connect-failed");
        JdkWhisperUpstreamClient client = new JdkWhisperUpstreamClient(
                properties(uri), harness.httpClient());
        SSLHandshakeException failure = new SSLHandshakeException(
                "sensitive-tls-message");
        LoggerCapture capture = capture();
        try {
            CompletableFuture<VoiceTranscriptionSession> connecting = client.connect(
                            context, listener)
                    .toCompletableFuture();
            WebSocket.Listener adapter = harness.listener().get();
            adapter.onError(harness.webSocket(), failure);
            harness.opening().completeExceptionally(failure);

            assertThatThrownBy(connecting::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCause(failure);
            assertThat(capture.messages())
                    .filteredOn(message -> message.contains("phase=CONNECT_FAILED"))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains("upstreamTarget=LOOPBACK", "upstreamPort=7896")
                            .doesNotContain("sensitive-tls-message"));
        } finally {
            capture.close();
        }
    }

    @Test
    void recordsOpenTransportErrorAndCloseWithoutSensitivePayloads() {
        URI uri = URI.create("wss://127.0.0.1:7896/ws");
        WebSocketHarness harness = webSocketHarness(uri);
        VoiceTranscriptionListener listener = mock(VoiceTranscriptionListener.class);
        VoiceDiagnosticContext context = new VoiceDiagnosticContext(
                "trace-callbacks", "edge-callbacks");
        JdkWhisperUpstreamClient client = new JdkWhisperUpstreamClient(
                properties(uri), harness.httpClient());
        LoggerCapture capture = capture();
        try {
            CompletableFuture<VoiceTranscriptionSession> connecting = client.connect(
                            context, listener)
                    .toCompletableFuture();
            WebSocket.Listener adapter = harness.listener().get();
            adapter.onOpen(harness.webSocket());
            harness.opening().complete(harness.webSocket());
            connecting.join();
            adapter.onError(
                    harness.webSocket(),
                    new java.io.IOException("sensitive-transport-message"));
            adapter.onClose(
                    harness.webSocket(),
                    1000,
                    "sensitive-close-reason");

            assertThat(capture.messages())
                    .anyMatch(message -> message.contains("phase=WEBSOCKET_OPEN"))
                    .anyMatch(message -> message.contains("phase=TRANSPORT_ERROR")
                            && message.contains("exceptionType=IOException"))
                    .anyMatch(message -> message.contains("phase=CLOSED")
                            && message.contains("closeCode=1000"))
                    .allSatisfy(message -> assertThat(message).doesNotContain(
                            "sensitive-transport-message",
                            "sensitive-close-reason"));
        } finally {
            capture.close();
        }
    }

    @Test
    void completesTrustedTlsHandshakeAndEmptyTranscriptionTurn() throws Exception {
        assumeTrue(Boolean.getBoolean("voice.integration.enabled"));
        URI uri = URI.create(System.getProperty("voice.integration.uri"));
        String certificate = System.getProperty("voice.integration.cert");
        VoiceProperties properties = new VoiceProperties(
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
                uri,
                certificate,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2));

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> finalEvent = new AtomicReference<>();
        VoiceTranscriptionListener listener = new VoiceTranscriptionListener() {
            @Override
            public void onText(String message) {
                if (message.contains("\"type\":\"session.ready\"")) {
                    ready.countDown();
                }
                if (message.contains("\"type\":\"transcript.final\"")) {
                    finalEvent.set(message);
                    completed.countDown();
                }
            }

            @Override
            public void onClosed(int statusCode, String reason) {
                // Python 在 final 后正常关闭连接，final 事件本身才是该测试的完成证据。
            }

            @Override
            public void onError(Throwable cause) {
                failure.compareAndSet(null, cause);
                ready.countDown();
                completed.countDown();
            }
        };

        JdkWhisperUpstreamClient client = new JdkWhisperUpstreamClient(
                properties,
                new DefaultResourceLoader());
        Logger logger = (Logger) LoggerFactory.getLogger(
                JdkWhisperUpstreamClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            VoiceTranscriptionSession session = client.connect(
                            new VoiceDiagnosticContext(
                                    "trace-whisper-integration",
                                    "edge-whisper-integration"),
                            listener)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            session.sendText("{\"type\":\"session.start\",\"language\":\"auto\","
                            + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                            + "\"channels\":1}")
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            session.sendText("{\"type\":\"input.commit\"}")
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(completed.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(finalEvent.get()).contains("\"text\":\"\"");
            int expectedPort = uri.getPort() >= 0
                    ? uri.getPort()
                    : "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            List<String> messages = logs.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("phase=CONNECT_STARTED")
                            && message.contains("upstreamTarget=LOOPBACK"))
                    .anyMatch(message -> message.contains("phase=WEBSOCKET_OPEN")
                            && message.contains("upstreamPort=" + expectedPort))
                    .allSatisfy(message -> assertThat(message).doesNotContain(
                            "session.start", "pcm_s16le"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private static WebSocketHarness webSocketHarness(URI uri) {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket webSocket = mock(WebSocket.class);
        CompletableFuture<WebSocket> opening = new CompletableFuture<>();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.buildAsync(eq(uri), any(WebSocket.Listener.class)))
                .thenAnswer(invocation -> {
                    listener.set(invocation.getArgument(1));
                    return opening;
                });
        return new WebSocketHarness(httpClient, webSocket, opening, listener);
    }

    private static VoiceProperties properties(URI uri) {
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
                uri,
                "unused-test-certificate",
                Duration.ofSeconds(5),
                Duration.ofMinutes(2));
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                JdkWhisperUpstreamClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private record WebSocketHarness(
            HttpClient httpClient,
            WebSocket webSocket,
            CompletableFuture<WebSocket> opening,
            AtomicReference<WebSocket.Listener> listener) {}

    private record LoggerCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private List<String> messages() {
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
