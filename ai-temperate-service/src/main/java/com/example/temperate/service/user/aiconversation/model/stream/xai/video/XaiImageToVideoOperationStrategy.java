package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构造 xAI 单图生成视频请求，输入图片只通过短期 OSS HTTPS URL 传递。
 */
@Component
public final class XaiImageToVideoOperationStrategy
        implements XaiVideoOperationStrategy {

    private final ObjectMapper objectMapper;
    private final String path;

    public XaiImageToVideoOperationStrategy(ObjectMapper objectMapper) {
        this(objectMapper, AiConversationVideoGenerationProperties.officialDefaults());
    }

    @Autowired
    public XaiImageToVideoOperationStrategy(
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(properties).endpoints().generations();
    }

    @Override
    public AiConversationVideoMode mode() {
        return AiConversationVideoMode.IMAGE_TO_VIDEO;
    }

    @Override
    public XaiVideoStartRequest buildRequest(XaiVideoOperationContext context) {
        requireMode(context);
        ObjectNode body = base(context);
        body.putObject("image").put("url", context.requiredSingleInputUrl());
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
            throw new IllegalArgumentException("Image video mode is inconsistent.");
        }
    }
}
