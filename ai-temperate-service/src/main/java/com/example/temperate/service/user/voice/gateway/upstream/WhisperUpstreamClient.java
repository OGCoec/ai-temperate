package com.example.temperate.service.user.voice.gateway.upstream;

import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import java.util.concurrent.CompletionStage;

/**
 * 定义本机 Whisper WSS 的低层连接适配边界。
 */
public interface WhisperUpstreamClient {

    /**
     * 使用受信任证书建立本机 Whisper WebSocket，并显式携带脱敏诊断上下文。
     *
     * @param diagnosticContext 只用于异步日志关联的诊断信息
     * @param listener 上游消息、关闭和异常监听器
     * @return 异步建立的上游会话
     */
    CompletionStage<VoiceTranscriptionSession> connect(
            VoiceDiagnosticContext diagnosticContext,
            VoiceTranscriptionListener listener);
}
