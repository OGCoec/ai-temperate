package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 构造 xAI 多参考图生成视频请求，第一阶段明确不发送参考音频字段。
 */
@Component
public final class XaiReferenceToVideoOperationStrategy
        implements XaiVideoOperationStrategy {

    private final ObjectMapper objectMapper;
    private final String path;

    public XaiReferenceToVideoOperationStrategy(ObjectMapper objectMapper) {
        this(objectMapper, AiConversationVideoGenerationProperties.officialDefaults());
    }

    @Autowired
    public XaiReferenceToVideoOperationStrategy(
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(properties).endpoints().generations();
    }

    @Override
    public AiConversationVideoMode mode() {
        return AiConversationVideoMode.REFERENCE_TO_VIDEO;
    }

    @Override
    public XaiVideoStartRequest buildRequest(XaiVideoOperationContext context) {
        requireMode(context);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", context.modelName());
        body.put("prompt", context.prompt());
        ArrayNode images = body.putArray("reference_images");
        context.inputUrls().forEach(url -> images.addObject().put("url", url));
        body.put("duration", context.options().durationSeconds());
        body.put("aspect_ratio", context.options().aspectRatio().upstreamValue());
        body.put("resolution", context.options().resolution().upstreamValue());
        return new XaiVideoStartRequest(path, body);
    }

    private void requireMode(XaiVideoOperationContext context) {
        if (context.options().mode() != mode()) {
            throw new IllegalArgumentException("Reference video mode is inconsistent.");
        }
    }
}
