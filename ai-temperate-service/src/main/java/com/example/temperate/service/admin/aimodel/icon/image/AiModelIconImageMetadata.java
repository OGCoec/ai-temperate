package com.example.temperate.service.admin.aimodel.icon.image;

import java.util.Objects;

/**
 * 表示完成安全验证后的模型图标格式、显示边界、帧数和最终存储字节。
 *
 * <p>光栅图的存储字节保持原样，SVG 的存储字节来自安全 DOM 重新序列化；数组在边界处复制，
 * 避免后续调用方修改已经验证过的内容。</p>
 */
public record AiModelIconImageMetadata(
        AiModelIconImageFormat format,
        int width,
        int height,
        int frameCount,
        byte[] storageBytes) {

    public AiModelIconImageMetadata {
        Objects.requireNonNull(format);
        if (width <= 0 || height <= 0 || frameCount <= 0) {
            throw new IllegalArgumentException("Validated image metadata must be positive.");
        }
        storageBytes = Objects.requireNonNull(storageBytes).clone();
    }

    @Override
    public byte[] storageBytes() {
        return storageBytes.clone();
    }
}
