package com.example.temperate.service.user.voice.gateway;

/**
 * 接收本机 Whisper WSS 返回的文本事件、关闭状态和传输异常。
 */
public interface VoiceTranscriptionListener {

    void onText(String message);

    void onClosed(int statusCode, String reason);

    void onError(Throwable cause);
}
