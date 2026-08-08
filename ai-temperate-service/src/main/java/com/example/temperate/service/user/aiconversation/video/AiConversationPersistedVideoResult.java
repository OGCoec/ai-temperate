package com.example.temperate.service.user.aiconversation.video;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import java.util.Objects;

/**
 * 保存已持久化视频附件与可信媒体元数据，明确区分 OSS 交付状态和 xAI 供应商计费事实。
 */
public record AiConversationPersistedVideoResult(
        AiConversationAttachment attachment,
        long durationMillis,
        int width,
        int height,
        String contentType,
        long byteSize,
        String videoCodec,
        String objectKey,
        String storageProvider) {

    public AiConversationPersistedVideoResult {
        attachment = Objects.requireNonNull(attachment);
        if (durationMillis <= 0L
                || width <= 0
                || height <= 0
                || !"video/mp4".equalsIgnoreCase(contentType)
                || byteSize <= 0L
                || videoCodec == null
                || videoCodec.isBlank()
                || objectKey == null
                || objectKey.isBlank()
                || !"ALIYUN_OSS".equals(storageProvider)) {
            throw new IllegalArgumentException("Persisted video metadata is invalid.");
        }
    }
}
