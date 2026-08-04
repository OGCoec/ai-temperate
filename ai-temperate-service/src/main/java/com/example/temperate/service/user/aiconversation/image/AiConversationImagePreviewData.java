package com.example.temperate.service.user.aiconversation.image;

/**
 * 表示发往当前 SSE 观察者的一张完整可显示图片；Base64 只允许在当前进程内短暂存在。
 */
public record AiConversationImagePreviewData(
        String imageId,
        String phase,
        int index,
        String contentType,
        int width,
        int height,
        String base64) {
}
