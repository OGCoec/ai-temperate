package com.example.temperate.service.user.aiconversation.video;

import java.util.Locale;
import java.util.Set;

/**
 * 保存由独立 FC 探测并返回的可信输入视频元数据，避免主业务 JVM 下载视频或信任客户端声明。
 */
public record AiConversationVideoInputMetadata(
        long durationMillis,
        int width,
        int height,
        String codec) {

    private static final Set<String> SUPPORTED_CODECS = Set.of(
            "h264", "h265", "hevc", "av1");

    public AiConversationVideoInputMetadata {
        if (durationMillis <= 0L || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Video metadata dimensions and duration must be positive.");
        }
        if (codec == null || codec.isBlank()) {
            throw new IllegalArgumentException("Video codec is required.");
        }
        codec = codec.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CODECS.contains(codec)) {
            throw new IllegalArgumentException("Video codec is unsupported.");
        }
    }
}
