package com.example.temperate.service.user.aiconversation.image;

/**
 * 定义文字生成图片第一版允许的三种稳定画幅，前后端只交换枚举而不直接接收任意尺寸。
 */
public enum AiConversationImageAspect {
    SQUARE(1024, 1024),
    LANDSCAPE(1536, 1024),
    PORTRAIT(1024, 1536);

    private final int width;
    private final int height;

    AiConversationImageAspect(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String upstreamSize() {
        return width + "x" + height;
    }
}
