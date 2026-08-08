package com.example.temperate.service.user.aiconversation.image;

import java.util.Objects;

/**
 * 保存单个图片输出槽的限长安全计量证据，不承载 Prompt、图片正文、URL 或原始供应商响应。
 */
public record AiConversationImageMeteringEvidence(
        short outputIndex,
        AiConversationImageMeteringStatus status,
        String requestId,
        Long costTicks) {

    public AiConversationImageMeteringEvidence {
        if (outputIndex < 0 || outputIndex > 9) {
            throw new IllegalArgumentException(
                    "Image output index is out of range.");
        }
        status = Objects.requireNonNull(status);
        requestId = sanitizeRequestId(requestId);
        if (costTicks != null && costTicks < 0L) {
            throw new IllegalArgumentException("Image cost ticks are invalid.");
        }
        if (status == AiConversationImageMeteringStatus.COMPLETE
                && costTicks == null) {
            throw new IllegalArgumentException(
                    "Complete image cost evidence requires cost ticks.");
        }
        if (status != AiConversationImageMeteringStatus.COMPLETE) {
            costTicks = null;
        }
    }

    private static String sanitizeRequestId(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,128}")) {
            return "unavailable";
        }
        return normalized;
    }
}
