package com.example.temperate.service.user.aiconversation.video;

/**
 * 定义客户端视频清晰度档位与 xAI REST API 使用的固定字符串之间的映射。
 */
public enum AiConversationVideoResolution {
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080);

    private final String upstreamValue;
    private final int height;

    AiConversationVideoResolution(String upstreamValue, int height) {
        this.upstreamValue = upstreamValue;
        this.height = height;
    }

    public String upstreamValue() {
        return upstreamValue;
    }

    public int height() {
        return height;
    }

    /**
     * 根据可信视频探测高度选择编辑或延长的官方计费档位，超过 720p 的输入按输出上限 720p 估算。
     *
     * @param sourceHeight 输入视频像素高度
     * @return 继承并受 720p 上限约束的清晰度
     */
    public static AiConversationVideoResolution inheritedUpTo720p(
            int sourceHeight) {
        if (sourceHeight <= 0) {
            throw new IllegalArgumentException("Video height must be positive.");
        }
        return sourceHeight <= 480 ? P480 : P720;
    }
}
