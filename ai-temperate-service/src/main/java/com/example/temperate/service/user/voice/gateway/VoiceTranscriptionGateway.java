package com.example.temperate.service.user.voice.gateway;

import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import java.util.concurrent.CompletionStage;

/**
 * 建立经过证书校验的本机 Whisper WSS 会话并发送标准 session.start 首帧。
 */
public interface VoiceTranscriptionGateway {

    /**
     * 建立一个独占上游会话并发送已由 Web 层校验生成的首帧。
     *
     * <p>诊断上下文只沿调用链关联日志，不得参与连接准入或改变首帧内容。</p>
     *
     * @param diagnosticContext 脱敏的跨边界日志关联信息
     * @param upstreamStartMessage 发往 Whisper 的协议首帧
     * @param listener 上游事件监听器
     * @return 异步建立并完成首帧发送的会话
     */
    CompletionStage<VoiceTranscriptionSession> open(
            VoiceDiagnosticContext diagnosticContext,
            String upstreamStartMessage,
            VoiceTranscriptionListener listener);
}
