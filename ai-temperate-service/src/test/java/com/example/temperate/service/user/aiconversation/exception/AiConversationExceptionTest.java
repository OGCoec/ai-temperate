package com.example.temperate.service.user.aiconversation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.ClosedChannelException;
import org.junit.jupiter.api.Test;

/**
 * 验证受控 AI 会话异常保留内部根因，同时只公开稳定的业务码和停止原因。
 */
final class AiConversationExceptionTest {

    @Test
    void retainsCauseAndSafeReasonWithoutPublishingCauseMessage() {
        ClosedChannelException cause = new ClosedChannelException();

        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED,
                cause);

        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(failure.reason()).isEqualTo(
                AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED);
        assertThat(failure.getMessage()).isEqualTo("模型响应未能完成");
    }
}
