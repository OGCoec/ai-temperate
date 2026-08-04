package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 使用不可变白名单为 GPT Image 2 和 GPT Image 1.5 提供统一三档质量与标准画幅映射。
 */
@Service
public final class AiConversationImageProfileServiceImpl
        implements AiConversationImageProfileService {

    private static final Map<String, ModelProfiles> MODELS = Map.of(
            "gpt-image-2", image2(),
            "gpt-image-1.5", image15());

    @Override
    public AiConversationImageProfile required(
            String modelName,
            AiConversationReasoningEffort productTier,
            AiConversationImageAspect aspect) {
        ModelProfiles model = requiredModel(modelName);
        Map<AiConversationImageAspect, AiConversationImageProfile> tier =
                model.profiles().get(productTier);
        if (tier == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "图片生成只支持 Low、Medium、High 三档。",
                    false);
        }
        AiConversationImageProfile profile = tier.get(aspect);
        if (profile == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "图片画幅不受支持。",
                    false);
        }
        return profile;
    }

    @Override
    public List<Short> supportedLevels(String modelName) {
        return requiredModel(modelName).levels();
    }

    @Override
    public List<AiConversationImageAspect> supportedAspects(String modelName) {
        requiredModel(modelName);
        return List.of(AiConversationImageAspect.values());
    }

    @Override
    public boolean supports(String modelName) {
        return modelName != null && MODELS.containsKey(normalize(modelName));
    }

    private static ModelProfiles requiredModel(String modelName) {
        ModelProfiles profiles = modelName == null
                ? null
                : MODELS.get(normalize(modelName));
        if (profiles == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                    "所选模型没有受支持的图片生成规格。",
                    false);
        }
        return profiles;
    }

    private static String normalize(String modelName) {
        return modelName.trim().toLowerCase(Locale.ROOT);
    }

    private static ModelProfiles image2() {
        EnumMap<AiConversationReasoningEffort,
                Map<AiConversationImageAspect, AiConversationImageProfile>> profiles =
                new EnumMap<>(AiConversationReasoningEffort.class);
        profiles.put(AiConversationReasoningEffort.LOW, tier(
                AiConversationImageQuality.LOW,
                AiConversationReasoningEffort.LOW));
        profiles.put(AiConversationReasoningEffort.MEDIUM, tier(
                AiConversationImageQuality.MEDIUM,
                AiConversationReasoningEffort.LOW));
        profiles.put(AiConversationReasoningEffort.HIGH, tier(
                AiConversationImageQuality.HIGH,
                AiConversationReasoningEffort.MEDIUM));
        return new ModelProfiles(
                Map.copyOf(profiles),
                List.of((short) 1, (short) 2, (short) 3));
    }

    private static ModelProfiles image15() {
        EnumMap<AiConversationReasoningEffort,
                Map<AiConversationImageAspect, AiConversationImageProfile>> profiles =
                new EnumMap<>(AiConversationReasoningEffort.class);
        profiles.put(AiConversationReasoningEffort.LOW, tier(
                AiConversationImageQuality.LOW,
                AiConversationReasoningEffort.LOW));
        profiles.put(AiConversationReasoningEffort.MEDIUM, tier(
                AiConversationImageQuality.MEDIUM,
                AiConversationReasoningEffort.LOW));
        profiles.put(AiConversationReasoningEffort.HIGH, tier(
                AiConversationImageQuality.HIGH,
                AiConversationReasoningEffort.MEDIUM));
        return new ModelProfiles(
                Map.copyOf(profiles),
                List.of((short) 1, (short) 2, (short) 3));
    }

    private static Map<AiConversationImageAspect, AiConversationImageProfile> tier(
            AiConversationImageQuality quality,
            AiConversationReasoningEffort reasoningEffort) {
        EnumMap<AiConversationImageAspect, AiConversationImageProfile> profiles =
                new EnumMap<>(AiConversationImageAspect.class);
        for (AiConversationImageAspect aspect : AiConversationImageAspect.values()) {
            profiles.put(aspect, new AiConversationImageProfile(
                    quality, aspect.width(), aspect.height(), reasoningEffort));
        }
        return Map.copyOf(profiles);
    }

    private record ModelProfiles(
            Map<AiConversationReasoningEffort,
                    Map<AiConversationImageAspect, AiConversationImageProfile>> profiles,
            List<Short> levels) {

        private ModelProfiles {
            profiles = Map.copyOf(profiles);
            levels = List.copyOf(levels);
        }
    }
}
