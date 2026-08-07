package com.example.temperate.service.user.voice.gateway;

import java.util.concurrent.CompletionStage;

/**
 * 建立经过证书校验的本机 Whisper WSS 会话并发送标准 session.start 首帧。
 */
public interface VoiceTranscriptionGateway {

    CompletionStage<VoiceTranscriptionSession> open(
            String upstreamStartMessage,
            VoiceTranscriptionListener listener);
}
