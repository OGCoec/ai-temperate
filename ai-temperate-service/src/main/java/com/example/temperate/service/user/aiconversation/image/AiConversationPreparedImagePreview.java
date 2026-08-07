package com.example.temperate.service.user.aiconversation.image;

import java.util.Base64;
import java.util.Objects;

/**
 * 承载经过 SSE 体积策略处理后的图片预览，确保 Broker 不再接触可能很大的原始图片字节。
 */
public record AiConversationPreparedImagePreview(
        String imageId,
        AiConversationGeneratedImagePhase phase,
        short outputIndex,
        Short partialImageIndex,
        String contentType,
        int width,
        int height,
        AiConversationImagePreviewKind previewKind,
        boolean requiresUpgrade,
        byte[] bytes) {

    public AiConversationPreparedImagePreview {
        imageId = requireText(imageId, "imageId");
        phase = Objects.requireNonNull(phase);
        contentType = requireText(contentType, "contentType");
        previewKind = Objects.requireNonNull(previewKind);
        if (outputIndex < 0 || outputIndex > 9
                || (phase == AiConversationGeneratedImagePhase.PARTIAL
                        && (partialImageIndex == null
                        || partialImageIndex < 0 || partialImageIndex > 2))
                || (phase == AiConversationGeneratedImagePhase.FINAL
                        && partialImageIndex != null)
                || width <= 0 || height <= 0
                || bytes == null || bytes.length == 0
                || (previewKind == AiConversationImagePreviewKind.FULL
                        && (phase != AiConversationGeneratedImagePhase.FINAL
                        || requiresUpgrade))
                || (previewKind == AiConversationImagePreviewKind.THUMBNAIL
                        && !requiresUpgrade)) {
            throw new IllegalArgumentException("Prepared image preview metadata is invalid.");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * 只对已经受体积上限约束的预览快照编码，禁止重新读取原始生成图片。
     *
     * @return 可放入 SSE JSON 数据的标准 Base64
     */
    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
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
