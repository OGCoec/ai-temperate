package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构造 xAI 视频编辑请求，并禁止发送由输入视频继承的时长、比例和清晰度。
 */
@Component
public final class XaiVideoEditOperationStrategy
        implements XaiVideoOperationStrategy {

    private final ObjectMapper objectMapper;
    private final String path;

    public XaiVideoEditOperationStrategy(ObjectMapper objectMapper) {
        this(objectMapper, AiConversationVideoGenerationProperties.officialDefaults());
    }

    @Autowired
    public XaiVideoEditOperationStrategy(
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(properties).endpoints().edits();
    }

    @Override
    public AiConversationVideoMode mode() {
        return AiConversationVideoMode.VIDEO_EDIT;
    }

    @Override
    public XaiVideoStartRequest buildRequest(XaiVideoOperationContext context) {
        requireMode(context);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", context.modelName());
        body.put("prompt", context.prompt());
        body.putObject("video").put("url", context.requiredSingleInputUrl());
        return new XaiVideoStartRequest(path, body);
    }

    private void requireMode(XaiVideoOperationContext context) {
        if (context.options().mode() != mode()) {
            throw new IllegalArgumentException("Video edit mode is inconsistent.");
        }
    }
}
