package com.example.temperate.service.user.aiconversation.image;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Objects;

/**
 * 承载一次已经完整 Base64 解码的上游图片事件，字节仅在当前进程短暂流转且不得进入数据库、Redis 或消息队列。
 */
public record AiConversationGeneratedImage(
        String imageId,
        AiConversationGeneratedImagePhase phase,
        short outputIndex,
        Short partialImageIndex,
        String contentType,
        int width,
        int height,
        byte[] bytes) {

    public AiConversationGeneratedImage {
        imageId = requireText(imageId, "imageId");
        phase = Objects.requireNonNull(phase);
        contentType = requireText(contentType, "contentType");
        if (outputIndex < 0 || outputIndex > 9
                || (phase == AiConversationGeneratedImagePhase.PARTIAL
                        && (partialImageIndex == null
                        || partialImageIndex < 0 || partialImageIndex > 2))
                || (phase == AiConversationGeneratedImagePhase.FINAL
                        && partialImageIndex != null)
                || width <= 0 || height <= 0
                || bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Generated image metadata is invalid.");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * 直接从内部不可变快照编码预览，避免为了只读编码额外复制整张图片。
     */
    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 为只读解码器打开内部快照流，避免大图压缩前再复制一份完整原始字节数组。
     *
     * @return 不能修改底层快照的只读字节输入流
     */
    public InputStream openStream() {
        return new ByteArrayInputStream(bytes);
    }

    public int sizeBytes() {
        return bytes.length;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
