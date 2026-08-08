package com.example.temperate.service.user.aiconversation.video;

/**
 * 承载主业务服务发送给 FC 的视频搬运引用，只有源临时 URL 与目标 OSS Key，不包含媒体内容或 OSS 凭据。
 */
public record AiConversationVideoTransferCommand(
        String transferId,
        String sourceUrl,
        String targetObjectKey,
        String expectedContentType,
        long maximumBytes) {

    public AiConversationVideoTransferCommand {
        if (transferId == null
                || !transferId.matches("^[A-Za-z0-9_-]{38}$")
                || sourceUrl == null
                || !sourceUrl.startsWith("https://")
                || targetObjectKey == null
                || targetObjectKey.isBlank()
                || !"video/mp4".equalsIgnoreCase(expectedContentType)
                || maximumBytes <= 0L) {
            throw new IllegalArgumentException("Video transfer command is invalid.");
        }
    }
}
