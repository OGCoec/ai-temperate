package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构造 xAI 文本生成视频请求，只发送模型、提示词、时长、比例和清晰度。
 */
@Component
public final class XaiTextToVideoOperationStrategy
        implements XaiVideoOperationStrategy {

    private final ObjectMapper objectMapper;
    private final String path;

    public XaiTextToVideoOperationStrategy(ObjectMapper objectMapper) {
        this(objectMapper, AiConversationVideoGenerationProperties.officialDefaults());
    }

    @Autowired
    public XaiTextToVideoOperationStrategy(
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(properties).endpoints().generations();
    }

    @Override
    public AiConversationVideoMode mode() {
        return AiConversationVideoMode.TEXT_TO_VIDEO;
    }

    @Override
    public XaiVideoStartRequest buildRequest(XaiVideoOperationContext context) {
        requireMode(context);
        ObjectNode body = base(context);
        body.put("duration", context.options().durationSeconds());
        body.put("aspect_ratio", context.options().aspectRatio().upstreamValue());
        body.put("resolution", context.options().resolution().upstreamValue());
        return new XaiVideoStartRequest(path, body);
    }

    private ObjectNode base(XaiVideoOperationContext context) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", context.modelName());
        body.put("prompt", context.prompt());
        return body;
    }

    private void requireMode(XaiVideoOperationContext context) {
        if (context.options().mode() != mode()) {
            throw new IllegalArgumentException("Text video mode is inconsistent.");
        }
    }
}
