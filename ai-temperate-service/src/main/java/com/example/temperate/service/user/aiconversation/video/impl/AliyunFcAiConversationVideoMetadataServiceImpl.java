package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoInputMetadata;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMetadataProbeCommand;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMetadataService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 通过 FC 的 probe 操作读取输入 MP4 的可信时长、尺寸和编码，避免主业务 JVM 建立媒体下载连接。
 */
@Service
public final class AliyunFcAiConversationVideoMetadataServiceImpl
        implements AiConversationVideoMetadataService {

    private final AliyunFcAiConversationVideoBridgeClient client;

    public AliyunFcAiConversationVideoMetadataServiceImpl(
            AliyunFcAiConversationVideoBridgeClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public AiConversationVideoInputMetadata probe(
            AiConversationVideoMetadataProbeCommand command) {
        Objects.requireNonNull(command);
        ProbeResponse response = client.invoke(
                "probe", command, ProbeResponse.class);
        return new AiConversationVideoInputMetadata(
                response.durationMillis(),
                response.width(),
                response.height(),
                response.videoCodec());
    }

    /**
     * 映射 FC 探测结果的最小字段集合，未知字段由 Jackson 忽略。
     */
    private record ProbeResponse(
            long durationMillis,
            int width,
            int height,
            String videoCodec) {
    }
}
