package com.example.temperate.service.user.voice.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定语音票据、公开 WebSocket、本机 Whisper 上游及有界排队协议的统一配置。
 *
 * <p>该配置固定五分钟音频边界、三路准入和五人等待队列，并拒绝明文上游；
 * 证书加载和网络连接由上游客户端负责。</p>
 */
@ConfigurationProperties(prefix = "app.voice")
public record VoiceProperties(
        boolean enabled,
        String publicPath,
        Duration ticketTtl,
        Duration ticketRateWindow,
        int ticketRateLimit,
        Duration maxDuration,
        Duration partialInterval,
        int maxActiveSessions,
        int waitingQueueCapacity,
        Duration queueWaitTimeout,
        List<String> allowedOrigins,
        URI upstreamUri,
        String upstreamTrustCertificate,
        Duration upstreamConnectTimeout,
        Duration finalTimeout) {

    public VoiceProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (publicPath == null || !publicPath.startsWith("/")
                || ticketTtl == null || ticketTtl.isZero() || ticketTtl.isNegative()
                || ticketRateWindow == null || ticketRateWindow.isZero()
                || ticketRateWindow.isNegative() || ticketRateLimit < 1
                || maxDuration == null || !Duration.ofMinutes(5).equals(maxDuration)
                || partialInterval == null || partialInterval.isZero()
                || partialInterval.isNegative()
                || maxActiveSessions < 1 || maxActiveSessions > 4
                || waitingQueueCapacity < 0 || waitingQueueCapacity > 32
                || queueWaitTimeout == null || queueWaitTimeout.isNegative()
                || queueWaitTimeout.isZero() || queueWaitTimeout.compareTo(Duration.ofMinutes(5)) > 0
                || upstreamUri == null || !"wss".equalsIgnoreCase(upstreamUri.getScheme())
                || upstreamTrustCertificate == null || upstreamTrustCertificate.isBlank()
                || upstreamConnectTimeout == null || upstreamConnectTimeout.isNegative()
                || upstreamConnectTimeout.isZero()
                || finalTimeout == null || finalTimeout.isNegative() || finalTimeout.isZero()) {
            throw new IllegalStateException("Voice transcription configuration is invalid.");
        }
    }

    public long maxAudioBytes() {
        return maxDuration.toMillis() * 32L;
    }
}
