package com.example.temperate.service.user.aiconversation.image;

import java.util.Objects;

/**
 * 承载一次已经完整 Base64 解码的上游图片事件，字节仅在当前进程短暂流转且不得进入数据库、Redis 或消息队列。
 */
public record AiConversationGeneratedImage(
        String imageId,
        AiConversationGeneratedImagePhase phase,
        int index,
        String contentType,
        int width,
        int height,
        byte[] bytes) {

    public AiConversationGeneratedImage {
        imageId = requireText(imageId, "imageId");
        phase = Objects.requireNonNull(phase);
        contentType = requireText(contentType, "contentType");
        if (index < 0 || index > 3 || width <= 0 || height <= 0
                || bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Generated image metadata is invalid.");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
