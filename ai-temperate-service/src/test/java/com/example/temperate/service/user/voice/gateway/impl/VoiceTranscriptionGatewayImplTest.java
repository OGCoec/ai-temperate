package com.example.temperate.service.user.voice.gateway.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.gateway.upstream.WhisperUpstreamClient;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 验证语音网关只记录 Whisper 首帧发送阶段，不输出首帧正文或异常消息。
 */
final class VoiceTranscriptionGatewayImplTest {

    @Test
    void logsSessionStartSentWithoutLoggingPayload() {
        WhisperUpstreamClient upstreamClient = mock(WhisperUpstreamClient.class);
        VoiceTranscriptionSession session = mock(VoiceTranscriptionSession.class);
        VoiceTranscriptionListener listener = mock(VoiceTranscriptionListener.class);
        VoiceDiagnosticContext context =
                new VoiceDiagnosticContext("trace-gateway", "edge-gateway");
        String startMessage = "{\"type\":\"session.start\",\"secret\":\"do-not-log\"}";
        when(upstreamClient.connect(context, listener))
                .thenReturn(CompletableFuture.completedFuture(session));
        when(session.sendText(startMessage))
                .thenReturn(CompletableFuture.completedFuture(null));
        VoiceTranscriptionGatewayImpl gateway =
                new VoiceTranscriptionGatewayImpl(upstreamClient);
        LoggerCapture capture = capture();
        try {
            VoiceTranscriptionSession opened = gateway.open(
                            context, startMessage, listener)
                    .toCompletableFuture()
                    .join();

            assertThat(opened).isSameAs(session);
            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "event=voice_whisper_upstream_lifecycle",
                        "traceId=trace-gateway",
                        "edgeRay=edge-gateway",
                        "phase=SESSION_START_SENT",
                        "exceptionType=ABSENT");
                assertThat(message).doesNotContain("session.start", "do-not-log");
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void closesConnectedSessionAndLogsOnlyExceptionTypeWhenStartSendFails() {
        WhisperUpstreamClient upstreamClient = mock(WhisperUpstreamClient.class);
        VoiceTranscriptionSession session = mock(VoiceTranscriptionSession.class);
        VoiceTranscriptionListener listener = mock(VoiceTranscriptionListener.class);
        VoiceDiagnosticContext context =
                new VoiceDiagnosticContext("trace-gateway-failed", "edge-gateway-failed");
        IOException failure = new IOException("sensitive-send-message");
        when(upstreamClient.connect(context, listener))
                .thenReturn(CompletableFuture.completedFuture(session));
        when(session.sendText("start"))
                .thenReturn(CompletableFuture.failedFuture(failure));
        when(session.close(1011, "SESSION_START_FAILED"))
                .thenReturn(CompletableFuture.completedFuture(null));
        VoiceTranscriptionGatewayImpl gateway =
                new VoiceTranscriptionGatewayImpl(upstreamClient);
        LoggerCapture capture = capture();
        try {
            assertThatThrownBy(() -> gateway.open(context, "start", listener)
                    .toCompletableFuture()
                    .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(failure);

            verify(session).close(1011, "SESSION_START_FAILED");
            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "phase=SESSION_START_FAILED",
                        "exceptionType=IOException");
                assertThat(message).doesNotContain("sensitive-send-message");
            });
        } finally {
            capture.close();
        }
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                VoiceTranscriptionGatewayImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private record LoggerCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private java.util.List<String> messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
