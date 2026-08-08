package com.example.temperate.service.user.aiconversation.video;

/**
 * 承载发送给 FC 的输入视频探测请求，主业务 JVM 只转交临时读取 URL 和大小上限，不读取视频字节。
 */
public record AiConversationVideoMetadataProbeCommand(
        String sourceUrl,
        String expectedContentType,
        long maximumBytes) {

    public AiConversationVideoMetadataProbeCommand {
        if (sourceUrl == null
                || !sourceUrl.startsWith("https://")
                || !"video/mp4".equalsIgnoreCase(expectedContentType)
                || maximumBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Video metadata probe command is invalid.");
        }
    }
}
