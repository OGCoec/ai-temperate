package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.video.AiConversationGeneratedVideo;
import java.util.Objects;

/**
 * 表示一次 xAI 视频状态查询的标准化结果，只有 DONE 状态允许携带临时视频 URL。
 */
public record XaiVideoPollResult(
        XaiVideoStatus status,
        int progress,
        AiConversationGeneratedVideo video,
        Long costInUsdTicks) {

    public XaiVideoPollResult {
        status = Objects.requireNonNull(status);
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("xAI video progress is invalid.");
        }
        if (status == XaiVideoStatus.DONE && video == null) {
            throw new IllegalArgumentException("Completed xAI video is missing.");
        }
        if (status != XaiVideoStatus.DONE && video != null) {
            throw new IllegalArgumentException(
                    "Non-completed xAI video cannot contain a URL.");
        }
        if (costInUsdTicks != null && costInUsdTicks < 0L) {
            throw new IllegalArgumentException("xAI video cost ticks are invalid.");
        }
    }
}
