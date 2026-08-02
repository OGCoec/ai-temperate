package com.example.temperate.service.user.aiconversation.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证会话复合游标隐藏内部数字并拒绝非规范或非正数的分页状态。
 */
final class AiConversationCursorCodecTest {

    private final AiConversationCursorCodec codec = new AiConversationCursorCodec();

    @Test
    void roundTripsLastMessageAndConversationIdentifiers() {
        byte[] conversationId = new byte[16];
        conversationId[15] = 7;

        String encoded = codec.encode(41L, conversationId);
        AiConversationCursorCodec.Cursor decoded = codec.decode(encoded);

        assertThat(encoded).hasSize(32).matches("^[A-Za-z0-9_-]{32}$");
        assertThat(decoded.lastMessageId()).isEqualTo(41L);
        assertThat(decoded.conversationId()).containsExactly(conversationId);
    }

    @Test
    void rejectsMalformedAndNonPositiveCursors() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(0L, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
