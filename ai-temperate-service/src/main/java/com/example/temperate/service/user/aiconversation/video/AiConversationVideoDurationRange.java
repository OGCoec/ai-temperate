package com.example.temperate.service.user.aiconversation.video;

/**
 * 表示用户模型目录中可直接选择的视频生成或延长秒数范围，不替代具体模式的服务端校验。
 */
public record AiConversationVideoDurationRange(
        int minimumSeconds,
        int maximumSeconds) {

    public AiConversationVideoDurationRange {
        if (minimumSeconds < 1 || maximumSeconds < minimumSeconds) {
            throw new IllegalArgumentException("Video duration range is invalid.");
        }
    }
}
