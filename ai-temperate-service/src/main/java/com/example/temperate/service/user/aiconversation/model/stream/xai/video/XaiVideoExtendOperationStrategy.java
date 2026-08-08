package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构造 xAI 视频延长请求，duration 仅代表新增片段且不覆盖输入视频形状。
 */
@Component
public final class XaiVideoExtendOperationStrategy
        implements XaiVideoOperationStrategy {

    private final ObjectMapper objectMapper;
    private final String path;

    public XaiVideoExtendOperationStrategy(ObjectMapper objectMapper) {
        this(objectMapper, AiConversationVideoGenerationProperties.officialDefaults());
    }

    @Autowired
    public XaiVideoExtendOperationStrategy(
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(properties).endpoints().extensions();
    }

    @Override
    public AiConversationVideoMode mode() {
        return AiConversationVideoMode.VIDEO_EXTEND;
    }

    @Override
    public XaiVideoStartRequest buildRequest(XaiVideoOperationContext context) {
        requireMode(context);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", context.modelName());
        body.put("prompt", context.prompt());
        body.putObject("video").put("url", context.requiredSingleInputUrl());
        body.put("duration", context.options().durationSeconds());
        return new XaiVideoStartRequest(path, body);
    }

    private void requireMode(XaiVideoOperationContext context) {
        if (context.options().mode() != mode()) {
            throw new IllegalArgumentException("Video extension mode is inconsistent.");
        }
    }
}
