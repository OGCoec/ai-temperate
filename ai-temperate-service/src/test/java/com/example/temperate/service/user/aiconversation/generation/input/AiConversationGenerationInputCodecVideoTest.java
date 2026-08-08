package com.example.temperate.service.user.aiconversation.generation.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证异步任务只冻结视频参数和可信元数据，不把临时 xAI 或 OSS 签名 URL 写入 JSONB。
 */
final class AiConversationGenerationInputCodecVideoTest {

    private final AiConversationGenerationInputCodec codec =
            new AiConversationGenerationInputCodec(new ObjectMapper());

    @Test
    void roundTripsVideoExtensionWithoutMediaUrl() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.VIDEO_EXTEND,
                        6,
                        AiConversationVideoResolution.P720,
                        null,
                        List.of("video-attachment"),
                        8_000L,
                        1920,
                        1080,
                        "h264");

        String json = codec.encode(
                List.of(),
                null,
                options,
                AiConversationWebSearchMode.OFF);
        AiConversationGenerationInputSnapshot decoded = codec.decode(json);

        assertThat(decoded.videoGeneration()).isEqualTo(options);
        assertThat(json).doesNotContain("http", "base64", "data:");
    }

    @Test
    void rejectsVideoGenerationCombinedWithWebSearch() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        5,
                        AiConversationVideoResolution.P480,
                        com.example.temperate.service.user.aiconversation.video
                                .AiConversationVideoAspectRatio.RATIO_16_9,
                        List.of(),
                        0L,
                        0,
                        0,
                        null);

        assertThatThrownBy(() -> codec.encode(
                List.of(),
                null,
                options,
                AiConversationWebSearchMode.REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
