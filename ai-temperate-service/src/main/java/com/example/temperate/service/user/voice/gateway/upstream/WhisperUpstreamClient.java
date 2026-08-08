package com.example.temperate.service.user.voice.gateway.upstream;

import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import java.util.concurrent.CompletionStage;

/**
 * 定义本机 Whisper WSS 的低层连接适配边界。
 */
public interface WhisperUpstreamClient {

    CompletionStage<VoiceTranscriptionSession> connect(VoiceTranscriptionListener listener);
}
