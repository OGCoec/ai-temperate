package com.example.temperate.service.user.voice.gateway.impl;

import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionGateway;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.gateway.upstream.WhisperUpstreamClient;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 把公开语音会话映射为一个独占的本机 Whisper WSS 会话。
 *
 * <p>该实现保持无状态；所有可变发送队列和连接生命周期都封装在返回的会话对象中。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class VoiceTranscriptionGatewayImpl implements VoiceTranscriptionGateway {

    private final WhisperUpstreamClient upstreamClient;

    public VoiceTranscriptionGatewayImpl(WhisperUpstreamClient upstreamClient) {
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
    }

    @Override
    public CompletionStage<VoiceTranscriptionSession> open(
            String upstreamStartMessage,
            VoiceTranscriptionListener listener) {
        Objects.requireNonNull(upstreamStartMessage);
        Objects.requireNonNull(listener);
        CompletableFuture<VoiceTranscriptionSession> opened = new CompletableFuture<>();
        upstreamClient.connect(listener).whenComplete((session, connectionError) -> {
            if (connectionError != null) {
                opened.completeExceptionally(connectionError);
                return;
            }
            // session.start 发送失败时必须主动关闭已经建立的 TLS Socket，避免无主上游会话长期占用 GPU 名额。
            session.sendText(upstreamStartMessage).whenComplete((ignored, sendError) -> {
                if (sendError == null) {
                    opened.complete(session);
                    return;
                }
                session.close(1011, "SESSION_START_FAILED");
                opened.completeExceptionally(sendError);
            });
        });
        return opened;
    }
}
