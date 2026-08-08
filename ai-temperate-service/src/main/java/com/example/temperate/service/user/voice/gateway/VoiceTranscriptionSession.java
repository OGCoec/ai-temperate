package com.example.temperate.service.user.voice.gateway;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * 表示一个前端语音连接独占的本机 Whisper 上游会话。
 */
public interface VoiceTranscriptionSession {

    CompletionStage<Void> sendText(String message);

    CompletionStage<Void> sendAudio(ByteBuffer audio);

    CompletionStage<Void> close(int statusCode, String reason);
}
