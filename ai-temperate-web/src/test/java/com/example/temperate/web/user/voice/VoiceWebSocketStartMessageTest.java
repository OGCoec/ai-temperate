package com.example.temperate.web.user.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.voice.VoiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证公开 WSS 首帧的票据、版本和 PCM 格式白名单。
 */
final class VoiceWebSocketStartMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String ticket = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(new byte[32]);

    @Test
    void stripsTicketBeforeBuildingLoopbackUpstreamMessage() {
        VoiceWebSocketStartMessage message = VoiceWebSocketStartMessage.parse(
                objectMapper,
                "{\"type\":\"session.start\",\"protocolVersion\":1,"
                        + "\"ticket\":\"" + ticket + "\",\"language\":\"auto\","
                        + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                        + "\"channels\":1}");

        assertThat(message.ticket()).isEqualTo(ticket);
        assertThat(message.upstreamJson(objectMapper))
                .contains("\"type\":\"session.start\"")
                .doesNotContain(ticket, "protocolVersion");
    }

    @Test
    void rejectsUnsupportedProtocolAndUnexpectedFields() {
        String base = "{\"type\":\"session.start\",\"protocolVersion\":%d,"
                + "\"ticket\":\"" + ticket + "\",\"language\":\"auto\","
                + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                + "\"channels\":1%s}";

        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                base.formatted(2, "")))
                .isInstanceOf(VoiceException.class);
        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                base.formatted(1, ",\"unexpected\":true")))
                .isInstanceOf(VoiceException.class);
    }

    @Test
    void rejectsTextThatOnlyLooksLikeANumericProtocolField() {
        assertThatThrownBy(() -> VoiceWebSocketStartMessage.parse(
                objectMapper,
                "{\"type\":\"session.start\",\"protocolVersion\":\"1\","
                        + "\"ticket\":\"" + ticket + "\",\"language\":\"auto\","
                        + "\"format\":\"pcm_s16le\",\"sampleRate\":16000,"
                        + "\"channels\":1}"))
                .isInstanceOf(VoiceException.class);
    }
}
