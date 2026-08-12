package com.example.temperate.service.user.voice.gateway.upstream;

import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 使用 Java 21 HttpClient 建立只信任指定 PEM 证书的本机 Whisper WSS 连接。
 *
 * <p>客户端不提供 trust-all 或主机名校验关闭路径；每个会话以串行 Future 链发送消息，并将待发送音频限制为一 MiB。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class JdkWhisperUpstreamClient implements WhisperUpstreamClient {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JdkWhisperUpstreamClient.class);
    private static final long MAX_PENDING_AUDIO_BYTES = 1024L * 1024L;

    private final VoiceProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public JdkWhisperUpstreamClient(
            VoiceProperties properties,
            ResourceLoader resourceLoader) {
        this(
                properties,
                HttpClient.newBuilder()
                        .connectTimeout(properties.upstreamConnectTimeout())
                        .sslContext(buildSslContext(resourceLoader.getResource(
                                properties.upstreamTrustCertificate())))
                        .build());
    }

    /**
     * 注入已经配置完成的 HTTP 客户端，只用于隔离验证异步 WebSocket 回调与日志去重机制。
     *
     * <p>生产构造器仍独占 TLS 信任材料的创建；该入口不注册为 Spring Bean，也不改变运行时连接配置。</p>
     */
    JdkWhisperUpstreamClient(
            VoiceProperties properties,
            HttpClient httpClient) {
        this.properties = Objects.requireNonNull(properties);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    @Override
    public CompletionStage<VoiceTranscriptionSession> connect(
            VoiceDiagnosticContext diagnosticContext,
            VoiceTranscriptionListener listener) {
        Objects.requireNonNull(diagnosticContext);
        Objects.requireNonNull(listener);
        long startedNanos = System.nanoTime();
        String upstreamTarget = upstreamTarget(properties.upstreamUri().getHost());
        int upstreamPort = upstreamPort();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean connectFailureLogged = new AtomicBoolean();
        ListenerAdapter adapter = new ListenerAdapter(
                listener,
                diagnosticContext,
                upstreamTarget,
                upstreamPort,
                startedNanos,
                opened,
                connectFailureLogged);
        logLifecycle(
                diagnosticContext,
                "CONNECT_STARTED",
                upstreamTarget,
                upstreamPort,
                startedNanos,
                -1,
                "ABSENT");
        CompletableFuture<WebSocket> opening = httpClient.newWebSocketBuilder()
                .connectTimeout(properties.upstreamConnectTimeout())
                .buildAsync(properties.upstreamUri(), adapter)
                .orTimeout(
                        properties.upstreamConnectTimeout().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
        return opening.whenComplete((webSocket, error) -> {
            if (error != null && connectFailureLogged.compareAndSet(false, true)) {
                logLifecycle(
                        diagnosticContext,
                        "CONNECT_FAILED",
                        upstreamTarget,
                        upstreamPort,
                        startedNanos,
                        -1,
                        safeExceptionType(unwrap(error)));
            }
        }).thenApply(webSocket -> new JdkSession(webSocket));
    }

    private int upstreamPort() {
        int configured = properties.upstreamUri().getPort();
        if (configured >= 0) {
            return configured;
        }
        return "wss".equalsIgnoreCase(properties.upstreamUri().getScheme()) ? 443 : 80;
    }

    private static String upstreamTarget(String host) {
        return "127.0.0.1".equals(host)
                        || "::1".equals(host)
                        || "localhost".equalsIgnoreCase(host)
                ? "LOOPBACK"
                : "INVALID";
    }

    private static SSLContext buildSslContext(Resource certificateResource) {
        try (InputStream input = certificateResource.getInputStream()) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates =
                    certificateFactory.generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IllegalStateException("Voice upstream trust certificate is empty.");
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("voice-upstream-" + index++, certificate);
            }
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Voice upstream trust certificate could not be loaded.",
                    exception);
        }
    }

    private static final class ListenerAdapter implements WebSocket.Listener {

        private final VoiceTranscriptionListener listener;
        private final VoiceDiagnosticContext diagnosticContext;
        private final String upstreamTarget;
        private final int upstreamPort;
        private final long startedNanos;
        private final AtomicBoolean opened;
        private final AtomicBoolean connectFailureLogged;
        private final StringBuilder textBuffer = new StringBuilder();

        private ListenerAdapter(
                VoiceTranscriptionListener listener,
                VoiceDiagnosticContext diagnosticContext,
                String upstreamTarget,
                int upstreamPort,
                long startedNanos,
                AtomicBoolean opened,
                AtomicBoolean connectFailureLogged) {
            this.listener = Objects.requireNonNull(listener);
            this.diagnosticContext = diagnosticContext;
            this.upstreamTarget = upstreamTarget;
            this.upstreamPort = upstreamPort;
            this.startedNanos = startedNanos;
            this.opened = opened;
            this.connectFailureLogged = connectFailureLogged;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.set(true);
            logLifecycle(
                    diagnosticContext,
                    "WEBSOCKET_OPEN",
                    upstreamTarget,
                    upstreamPort,
                    startedNanos,
                    -1,
                    "ABSENT");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            String completed = null;
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    completed = textBuffer.toString();
                    textBuffer.setLength(0);
                }
            }
            if (completed != null) {
                listener.onText(completed);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {
            VoiceException failure = new VoiceException(
                    VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                    "Whisper upstream returned an unexpected binary frame.",
                    false);
            logLifecycle(
                    diagnosticContext,
                    "TRANSPORT_ERROR",
                    upstreamTarget,
                    upstreamPort,
                    startedNanos,
                    -1,
                    safeExceptionType(failure));
            listener.onError(failure);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            logLifecycle(
                    diagnosticContext,
                    "CLOSED",
                    upstreamTarget,
                    upstreamPort,
                    startedNanos,
                    statusCode,
                    "ABSENT");
            listener.onClosed(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            boolean transportOpened = opened.get();
            String phase = transportOpened ? "TRANSPORT_ERROR" : "CONNECT_FAILED";
            if (transportOpened || connectFailureLogged.compareAndSet(false, true)) {
                logLifecycle(
                        diagnosticContext,
                        phase,
                        upstreamTarget,
                        upstreamPort,
                        startedNanos,
                        -1,
                        safeExceptionType(unwrap(error)));
            }
            listener.onError(error);
        }
    }

    private static final class JdkSession implements VoiceTranscriptionSession {

        private final WebSocket webSocket;
        private final AtomicLong pendingAudioBytes = new AtomicLong();
        private CompletableFuture<WebSocket> sendTail;

        private JdkSession(WebSocket webSocket) {
            this.webSocket = webSocket;
            this.sendTail = CompletableFuture.completedFuture(webSocket);
        }

        @Override
        public synchronized CompletionStage<Void> sendText(String message) {
            sendTail = sendTail.thenCompose(
                    ignored -> webSocket.sendText(message, true));
            return sendTail.thenApply(ignored -> null);
        }

        @Override
        public synchronized CompletionStage<Void> sendAudio(ByteBuffer audio) {
            ByteBuffer source = audio.asReadOnlyBuffer();
            int byteCount = source.remaining();
            long pending = pendingAudioBytes.addAndGet(byteCount);
            if (pending > MAX_PENDING_AUDIO_BYTES) {
                pendingAudioBytes.addAndGet(-byteCount);
                return CompletableFuture.failedFuture(new VoiceException(
                        VoiceErrorCode.VOICE_BACKPRESSURE,
                        "Voice upstream send queue exceeded its safe boundary.",
                        true));
            }
            ByteBuffer copy = ByteBuffer.allocate(byteCount);
            copy.put(source).flip();
            sendTail = sendTail
                    .thenCompose(ignored -> webSocket.sendBinary(copy, true))
                    .whenComplete((ignored, error) ->
                            pendingAudioBytes.addAndGet(-byteCount));
            return sendTail.thenApply(ignored -> null);
        }

        @Override
        public synchronized CompletionStage<Void> close(int statusCode, String reason) {
            sendTail = sendTail.thenCompose(
                    ignored -> webSocket.sendClose(statusCode, reason));
            return sendTail.thenApply(ignored -> null);
        }
    }

    private static void logLifecycle(
            VoiceDiagnosticContext context,
            String phase,
            String upstreamTarget,
            int upstreamPort,
            long startedNanos,
            int closeCode,
            String exceptionType) {
        String template = "event=voice_whisper_upstream_lifecycle traceId={} edgeRay={} "
                + "phase={} upstreamTarget={} upstreamPort={} elapsedMs={} "
                + "closeCode={} exceptionType={}";
        Object[] arguments = {
            context.traceId(),
            context.edgeRay(),
            phase,
            upstreamTarget,
            upstreamPort,
            elapsedMillis(startedNanos),
            closeCode,
            exceptionType
        };
        try {
            if ("CONNECT_FAILED".equals(phase) || "TRANSPORT_ERROR".equals(phase)) {
                LOGGER.warn(template, arguments);
            } else {
                LOGGER.info(template, arguments);
            }
        } catch (RuntimeException ignored) {
            // 日志后端异常不能改变 TLS/WebSocket Future 或监听器回调。
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeExceptionType(Throwable exception) {
        String type = exception.getClass().getSimpleName();
        return type.matches("^[A-Za-z0-9_$]{1,128}$") ? type : "INVALID";
    }
}
