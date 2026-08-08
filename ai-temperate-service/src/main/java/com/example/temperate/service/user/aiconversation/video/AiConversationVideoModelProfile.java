package com.example.temperate.service.user.aiconversation.video;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 表示一个 xAI 视频模型可使用的操作、各操作清晰度和官方价格快照。
 */
public record AiConversationVideoModelProfile(
        String modelName,
        List<AiConversationVideoMode> supportedModes,
        Map<AiConversationVideoMode, List<AiConversationVideoResolution>>
                supportedResolutions,
        AiConversationVideoPricingProfile pricing) {

    public AiConversationVideoModelProfile {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Video model name is required.");
        }
        supportedModes = List.copyOf(supportedModes);
        EnumMap<AiConversationVideoMode, List<AiConversationVideoResolution>> copied =
                new EnumMap<>(AiConversationVideoMode.class);
        supportedResolutions.forEach((mode, resolutions) ->
                copied.put(mode, List.copyOf(resolutions)));
        for (AiConversationVideoMode mode : supportedModes) {
            if (!copied.containsKey(mode) || copied.get(mode).isEmpty()) {
                throw new IllegalArgumentException(
                        "Video mode has no supported resolution.");
            }
        }
        supportedResolutions = Map.copyOf(copied);
        if (pricing == null) {
            throw new IllegalArgumentException("Video pricing is required.");
        }
    }

    public List<AiConversationVideoResolution> resolutions(
            AiConversationVideoMode mode) {
        return supportedResolutions.getOrDefault(mode, List.of());
    }
}
