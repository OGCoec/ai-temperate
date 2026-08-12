package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证 Voice WebSocket v2 子协议只接受固定协议和单个规范一次性 Ticket。
 */
final class VoiceWebSocketProtocolParserTest {

    private static final String TICKET = "A".repeat(43);

    @Test
    void acceptsEitherTokenOrder() {
        assertThat(VoiceWebSocketProtocolParser.parse(
                "ait-voice-v2, ait-ticket." + TICKET).rawTicket()).isEqualTo(TICKET);
        assertThat(VoiceWebSocketProtocolParser.parse(
                "ait-ticket." + TICKET + ", ait-voice-v2").rawTicket()).isEqualTo(TICKET);
    }

    @Test
    void rejectsMissingDuplicateUnknownAndOverlongTokens() {
        for (String value : new String[] {
                "",
                "ait-voice-v2",
                "ait-voice-v2, ait-voice-v2",
                "ait-voice-v2, ait-ticket.short",
                "ait-voice-v2, unknown",
                "x".repeat(129)}) {
            assertThatThrownBy(() -> VoiceWebSocketProtocolParser.parse(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
