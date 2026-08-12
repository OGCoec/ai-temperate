package com.example.temperate.service.user.voice.gateway.impl;

import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionGateway;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.gateway.upstream.WhisperUpstreamClient;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VoiceTranscriptionGatewayImpl.class);

    private final WhisperUpstreamClient upstreamClient;

    public VoiceTranscriptionGatewayImpl(WhisperUpstreamClient upstreamClient) {
        this.upstreamClient = Objects.requireNonNull(upstreamClient);
    }

    @Override
    public CompletionStage<VoiceTranscriptionSession> open(
            VoiceDiagnosticContext diagnosticContext,
            String upstreamStartMessage,
            VoiceTranscriptionListener listener) {
        Objects.requireNonNull(diagnosticContext);
        Objects.requireNonNull(upstreamStartMessage);
        Objects.requireNonNull(listener);
        CompletableFuture<VoiceTranscriptionSession> opened = new CompletableFuture<>();
        upstreamClient.connect(diagnosticContext, listener)
                .whenComplete((session, connectionError) -> {
            if (connectionError != null) {
                opened.completeExceptionally(connectionError);
                return;
            }
            // session.start 发送失败时必须主动关闭已经建立的 TLS Socket，避免无主上游会话长期占用 GPU 名额。
            session.sendText(upstreamStartMessage).whenComplete((ignored, sendError) -> {
                if (sendError == null) {
                    logLifecycle(
                            diagnosticContext,
                            "SESSION_START_SENT",
                            "ABSENT");
                    opened.complete(session);
                    return;
                }
                logLifecycle(
                        diagnosticContext,
                        "SESSION_START_FAILED",
                        safeExceptionType(unwrap(sendError)));
                session.close(1011, "SESSION_START_FAILED");
                opened.completeExceptionally(sendError);
            });
        });
        return opened;
    }

    private static void logLifecycle(
            VoiceDiagnosticContext context,
            String phase,
            String exceptionType) {
        String template = "event=voice_whisper_upstream_lifecycle traceId={} edgeRay={} "
                + "phase={} upstreamTarget=LOOPBACK upstreamPort=-1 elapsedMs=-1 "
                + "closeCode=-1 exceptionType={}";
        try {
            if ("SESSION_START_SENT".equals(phase)) {
                LOGGER.info(
                        template,
                        context.traceId(),
                        context.edgeRay(),
                        phase,
                        exceptionType);
            } else {
                LOGGER.warn(
                        template,
                        context.traceId(),
                        context.edgeRay(),
                        phase,
                        exceptionType);
            }
        } catch (RuntimeException ignored) {
            // 日志后端异常不能改变首帧发送 Future 的完成结果。
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeExceptionType(Throwable exception) {
        String type = exception.getClass().getSimpleName();
        return type.matches("^[A-Za-z0-9_$]{1,128}$") ? type : "INVALID";
    }
}
