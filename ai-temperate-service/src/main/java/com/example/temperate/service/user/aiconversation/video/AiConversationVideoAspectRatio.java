package com.example.temperate.service.user.aiconversation.video;

/**
 * 定义 xAI 视频生成允许的七种画面比例，编辑和延长模式不使用该参数。
 */
public enum AiConversationVideoAspectRatio {
    RATIO_1_1("1:1"),
    RATIO_16_9("16:9"),
    RATIO_9_16("9:16"),
    RATIO_4_3("4:3"),
    RATIO_3_4("3:4"),
    RATIO_3_2("3:2"),
    RATIO_2_3("2:3");

    private final String upstreamValue;

    AiConversationVideoAspectRatio(String upstreamValue) {
        this.upstreamValue = upstreamValue;
    }

    public String upstreamValue() {
        return upstreamValue;
    }
}
