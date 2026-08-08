package com.example.temperate.service.user.voice.gateway.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * 使用显式测试开关验证 Java 21 WSS 客户端能够通过指定 PEM 证书连接本机 Whisper。
 *
 * <p>默认测试套件不会访问运行中的服务；第二阶段联调必须同时提供回环 URI 和本地证书路径。</p>
 */
final class JdkWhisperUpstreamClientIntegrationTest {

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
        VoiceTranscriptionSession session = client.connect(listener)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        session.sendText("{\"type\":\"session.start\",\"language\":\"auto\","
                        + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,\"channels\":1}")
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
    }
}
