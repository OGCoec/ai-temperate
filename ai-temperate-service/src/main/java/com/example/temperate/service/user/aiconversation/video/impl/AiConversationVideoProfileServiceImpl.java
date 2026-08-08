package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoModelProfile;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoPricingProfile;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoProfileService;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 从强类型配置构建两种官方 xAI 视频模型档案，并拒绝跨模型自动降级或自动替换。
 */
@Service
public final class AiConversationVideoProfileServiceImpl
        implements AiConversationVideoProfileService {

    private final Map<String, AiConversationVideoModelProfile> profiles;

    public AiConversationVideoProfileServiceImpl(
            AiConversationVideoGenerationProperties properties) {
        Objects.requireNonNull(properties);
        LinkedHashMap<String, AiConversationVideoModelProfile> configured =
                new LinkedHashMap<>();
        AiConversationVideoModelProfile version15 = version15(properties.version15());
        AiConversationVideoModelProfile legacy = legacy(properties.legacy());
        configured.put(version15.modelName(), version15);
        if (configured.put(legacy.modelName(), legacy) != null) {
            throw new IllegalStateException("xAI video model names must be unique.");
        }
        profiles = Map.copyOf(configured);
    }

    @Override
    public AiConversationVideoModelProfile required(
            AiModelProvider provider,
            String modelName,
            AiConversationVideoMode mode,
            AiConversationVideoResolution resolution) {
        AiConversationVideoModelProfile profile = profile(provider, modelName);
        if (!profile.supportedModes().contains(mode)
                || !profile.resolutions(mode).contains(resolution)) {
            throw new IllegalArgumentException(
                    "The selected xAI video model does not support this mode or resolution.");
        }
        return profile;
    }

    @Override
    public boolean supports(AiModelProvider provider, String modelName) {
        return provider == AiModelProvider.XAI
                && modelName != null
                && profiles.containsKey(modelName);
    }

    @Override
    public List<AiConversationVideoMode> supportedModes(
            AiModelProvider provider,
            String modelName) {
        return profile(provider, modelName).supportedModes();
    }

    @Override
    public List<AiConversationVideoResolution> supportedResolutions(
            AiModelProvider provider,
            String modelName,
            AiConversationVideoMode mode) {
        return profile(provider, modelName).resolutions(mode);
    }

    private AiConversationVideoModelProfile profile(
            AiModelProvider provider,
            String modelName) {
        if (provider != AiModelProvider.XAI || modelName == null) {
            throw new IllegalArgumentException("xAI video model is required.");
        }
        AiConversationVideoModelProfile profile = profiles.get(modelName);
        if (profile == null) {
            throw new IllegalArgumentException("xAI video model is unsupported.");
        }
        return profile;
    }

    private static AiConversationVideoModelProfile version15(
            AiConversationVideoGenerationProperties.Version15Pricing pricing) {
        List<AiConversationVideoResolution> all = List.of(
                AiConversationVideoResolution.P480,
                AiConversationVideoResolution.P720,
                AiConversationVideoResolution.P1080);
        Map<AiConversationVideoMode, List<AiConversationVideoResolution>> modes =
                Map.of(
                        AiConversationVideoMode.TEXT_TO_VIDEO, all,
                        AiConversationVideoMode.IMAGE_TO_VIDEO, all,
                        AiConversationVideoMode.REFERENCE_TO_VIDEO, List.of(
                                AiConversationVideoResolution.P480,
                                AiConversationVideoResolution.P720));
        return new AiConversationVideoModelProfile(
                pricing.modelName(),
                List.of(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        AiConversationVideoMode.IMAGE_TO_VIDEO,
                        AiConversationVideoMode.REFERENCE_TO_VIDEO),
                modes,
                new AiConversationVideoPricingProfile(
                        Map.of(
                                AiConversationVideoResolution.P480,
                                pricing.p480OutputTicksPerSecond(),
                                AiConversationVideoResolution.P720,
                                pricing.p720OutputTicksPerSecond(),
                                AiConversationVideoResolution.P1080,
                                pricing.p1080OutputTicksPerSecond()),
                        pricing.imageInputTicksEach(),
                        0L));
    }

    private static AiConversationVideoModelProfile legacy(
            AiConversationVideoGenerationProperties.LegacyPricing pricing) {
        List<AiConversationVideoResolution> inherited = List.of(
                AiConversationVideoResolution.P480,
                AiConversationVideoResolution.P720);
        return new AiConversationVideoModelProfile(
                pricing.modelName(),
                List.of(
                        AiConversationVideoMode.VIDEO_EDIT,
                        AiConversationVideoMode.VIDEO_EXTEND),
                Map.of(
                        AiConversationVideoMode.VIDEO_EDIT, inherited,
                        AiConversationVideoMode.VIDEO_EXTEND, inherited),
                new AiConversationVideoPricingProfile(
                        Map.of(
                                AiConversationVideoResolution.P480,
                                pricing.p480OutputTicksPerSecond(),
                                AiConversationVideoResolution.P720,
                                pricing.p720OutputTicksPerSecond()),
                        pricing.imageInputTicksEach(),
                        pricing.videoInputTicksPerSecond()));
    }
}
