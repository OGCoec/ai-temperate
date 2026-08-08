package com.example.temperate.service.user.aiconversation.video;

import java.net.URI;

/**
 * 表示 xAI 已生成但尚未持久化的视频定位信息；临时 URL 只能由 Worker 立即交给 FC，禁止落库或返回前端。
 */
public record AiConversationGeneratedVideo(
        String requestId,
        String ephemeralUrl,
        long durationMillis,
        String model,
        boolean respectModeration) {

    public AiConversationGeneratedVideo {
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("xAI video request ID is invalid.");
        }
        try {
            URI uri = URI.create(ephemeralUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "xAI video URL must use HTTPS.");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("xAI video URL is invalid.", failure);
        }
        if (durationMillis <= 0L) {
            throw new IllegalArgumentException("xAI video duration must be positive.");
        }
        model = model == null || model.isBlank() ? "unavailable" : model.trim();
    }
}
