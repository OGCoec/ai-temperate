package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 验证 xAI 异步视频响应只提取状态、进度、临时 URL、时长和精确成本 ticks。
 */
final class XaiVideoResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XaiVideoResponseMapper mapper = new XaiVideoResponseMapper();

    @Test
    void mapsStartRequestId() throws Exception {
        assertThat(mapper.mapStart(objectMapper.readTree(
                "{\"request_id\":\"request_123\"}")).requestId())
                .isEqualTo("request_123");
    }

    @Test
    void mapsCompletedVideoAndExactCostTicks() throws Exception {
        XaiVideoPollResult result = mapper.mapPoll(objectMapper.readTree("""
                {
                  "status": "done",
                  "progress": 100,
                  "model": "grok-imagine-video-1.5",
                  "video": {
                    "url": "https://vidgen.x.ai/output.mp4",
                    "duration": 6.5,
                    "respect_moderation": true
                  },
                  "usage": {"cost_in_usd_ticks": 8400000000}
                }
                """));

        assertThat(result.status()).isEqualTo(XaiVideoStatus.DONE);
        assertThat(result.progress()).isEqualTo(100);
        assertThat(result.video().ephemeralUrl())
                .isEqualTo("https://vidgen.x.ai/output.mp4");
        assertThat(result.video().durationMillis()).isEqualTo(6_500L);
        assertThat(result.costInUsdTicks()).isEqualTo(8_400_000_000L);
    }

    @Test
    void rejectsNonHttpsVideoUrl() throws Exception {
        assertThatThrownBy(() -> mapper.mapPoll(objectMapper.readTree("""
                {
                  "status": "done",
                  "progress": 100,
                  "video": {"url": "http://127.0.0.1/video.mp4", "duration": 4},
                  "usage": {"cost_in_usd_ticks": 1}
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
