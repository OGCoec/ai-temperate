package com.example.temperate.service.user.aiconversation.video;

/**
 * 表示 FC 已完成 OSS 分片上传并经 HEAD 校验后的可信小型元数据，不携带视频内容或 xAI 临时地址。
 */
public record AiConversationVideoTransferResult(
        String objectKey,
        String publicUrl,
        long byteSize,
        String contentType,
        long durationMillis,
        int width,
        int height,
        String videoCodec,
        String etag,
        String checksumSha256) {

    public AiConversationVideoTransferResult {
        if (objectKey == null
                || objectKey.isBlank()
                || publicUrl == null
                || !publicUrl.startsWith("https://")
                || byteSize <= 0L
                || !"video/mp4".equalsIgnoreCase(contentType)
                || durationMillis <= 0L
                || width <= 0
                || height <= 0
                || videoCodec == null
                || videoCodec.isBlank()) {
            throw new IllegalArgumentException("Video transfer result is invalid.");
        }
    }
}
