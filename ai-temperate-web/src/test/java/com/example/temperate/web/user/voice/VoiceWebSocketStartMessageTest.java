package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.voice.VoiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 验证公开 WSS 初始化帧只接受 v2 语音参数，并拒绝遗留 Ticket 字段。
 */
final class VoiceWebSocketStartMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Test
    void acceptsVersionTwoWithoutTicket() {
        VoiceWebSocketStartMessage message = VoiceWebSocketStartMessage.parse(
                objectMapper,
                "{\"type\":\"session.start\",\"protocolVersion\":2,"
                        + "\"language\":\"auto\","
                        + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                        + "\"channels\":1}");

        assertThat(message.upstreamJson(objectMapper))
                .contains("\"type\":\"session.start\"")
                .doesNotContain("protocolVersion", "ticket");
    }

    @Test
    void rejectsUnsupportedProtocolAndUnexpectedFields() {
        String base = "{\"type\":\"session.start\",\"protocolVersion\":%d,"
                + "\"language\":\"auto\","
                + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                + "\"channels\":1%s}";

        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                base.formatted(1, "")))
                .isInstanceOf(VoiceException.class);
        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                base.formatted(2, ",\"ticket\":\"" + "A".repeat(43) + "\"")))
                .isInstanceOf(VoiceException.class);
    }

    @Test
    void rejectsTextThatOnlyLooksLikeANumericProtocolField() {
        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                "{\"type\":\"session.start\",\"protocolVersion\":\"2\","
                        + "\"language\":\"auto\","
                        + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                        + "\"channels\":1}"))
                .isInstanceOf(VoiceException.class);
    }
}
