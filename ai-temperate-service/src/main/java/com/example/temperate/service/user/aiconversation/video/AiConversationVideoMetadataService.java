package com.example.temperate.service.user.aiconversation.video;

/**
 * 通过独立 FC 探测 OSS 输入视频的可信时长、尺寸和编码，确保媒体字节不进入主业务 JVM。
 */
public interface AiConversationVideoMetadataService {

    AiConversationVideoInputMetadata probe(
            AiConversationVideoMetadataProbeCommand command);
}
