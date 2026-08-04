package com.example.temperate.service.user.aiconversation.image;

/**
 * 定义 CLIProxyAPI 图片生成请求允许使用的上游质量白名单。
 */
public enum AiConversationImageQuality {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String upstreamValue;

    AiConversationImageQuality(String upstreamValue) {
        this.upstreamValue = upstreamValue;
    }

    public String upstreamValue() {
        return upstreamValue;
    }
}
