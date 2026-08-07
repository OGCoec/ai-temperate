package com.example.temperate.service.user.voice.gateway.upstream;

import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
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
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

    private static final long MAX_PENDING_AUDIO_BYTES = 1024L * 1024L;

    private final VoiceProperties properties;
    private final HttpClient httpClient;

    public JdkWhisperUpstreamClient(
            VoiceProperties properties,
            ResourceLoader resourceLoader) {
        this.properties = Objects.requireNonNull(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.upstreamConnectTimeout())
                .sslContext(buildSslContext(
                        resourceLoader.getResource(properties.upstreamTrustCertificate())))
                .build();
    }

    @Override
    public CompletionStage<VoiceTranscriptionSession> connect(
            VoiceTranscriptionListener listener) {
        ListenerAdapter adapter = new ListenerAdapter(listener);
        return httpClient.newWebSocketBuilder()
                .connectTimeout(properties.upstreamConnectTimeout())
                .buildAsync(properties.upstreamUri(), adapter)
                .orTimeout(
                        properties.upstreamConnectTimeout().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(webSocket -> new JdkSession(webSocket));
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
        private final StringBuilder textBuffer = new StringBuilder();

        private ListenerAdapter(VoiceTranscriptionListener listener) {
            this.listener = Objects.requireNonNull(listener);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
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
            listener.onError(new VoiceException(
                    VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                    "Whisper upstream returned an unexpected binary frame.",
                    false));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            listener.onClosed(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
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
}
