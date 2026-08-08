package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 为管理员已授予图片能力且协议兼容的模型提供统一三档质量与标准画幅映射。
 *
 * <p>模型能否使用图片协议由模型目录能力集合决定，本服务不再通过模型名称维护第二份白名单。</p>
 */
@Service
public final class AiConversationImageProfileServiceImpl
        implements AiConversationImageProfileService {

    private static final ModelProfiles OPENAI_PROFILES = openAiProfiles();
    private static final ModelProfiles XAI_PROFILES = xaiProfiles();
    private static final ModelProfiles GOOGLE_PROFILES = googleProfiles();

    @Override
    public AiConversationImageProfile required(
            AiModelProvider provider,
            String modelName,
            AiConversationReasoningEffort productTier,
            AiConversationImageAspect aspect) {
        ModelProfiles model = requiredModel(provider, modelName);
        Map<AiConversationImageAspect, AiConversationImageProfile> tier =
                model.profiles().get(productTier);
        if (tier == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    unsupportedTierMessage(provider),
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

    private static String unsupportedTierMessage(AiModelProvider provider) {
        return switch (provider) {
            case OPENAI -> "OpenAI 图片生成只支持 Low、Medium、High 三档。";
            case XAI -> "xAI 图片生成只支持 1k 和 2k 档位。";
            case GOOGLE -> "Google 图片生成只支持 0.5K、1K、2K 和 4K 四档。";
            case ANTHROPIC -> "Anthropic 当前不提供图片生成协议。";
        };
    }

    @Override
    public List<Short> supportedLevels(
            AiModelProvider provider,
            String modelName) {
        return requiredModel(provider, modelName).levels();
    }

    @Override
    public List<AiConversationImageAspect> supportedAspects(
            AiModelProvider provider,
            String modelName) {
        requiredModel(provider, modelName);
        return List.of(AiConversationImageAspect.values());
    }

    @Override
    public boolean supports(AiModelProvider provider, String modelName) {
        return provider != null
                && provider != AiModelProvider.ANTHROPIC
                && modelName != null
                && !modelName.isBlank();
    }

    private static ModelProfiles requiredModel(
            AiModelProvider provider,
            String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                    "所选模型没有受支持的图片生成规格。",
                    false);
        }
        return switch (provider) {
            case OPENAI -> OPENAI_PROFILES;
            case XAI -> XAI_PROFILES;
            case GOOGLE -> GOOGLE_PROFILES;
            case ANTHROPIC -> throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                    "Anthropic 当前不提供图片生成协议。",
                    false);
        };
    }

    private static ModelProfiles openAiProfiles() {
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

    private static ModelProfiles xaiProfiles() {
        EnumMap<AiConversationReasoningEffort,
                Map<AiConversationImageAspect, AiConversationImageProfile>> profiles =
                new EnumMap<>(AiConversationReasoningEffort.class);
        profiles.put(AiConversationReasoningEffort.LOW, tier(
                AiConversationImageQuality.LOW,
                AiConversationReasoningEffort.LOW));
        profiles.put(AiConversationReasoningEffort.HIGH, tier(
                AiConversationImageQuality.HIGH,
                AiConversationReasoningEffort.MEDIUM,
                2));
        return new ModelProfiles(
                Map.copyOf(profiles),
                List.of((short) 1, (short) 3));
    }

    private static ModelProfiles googleProfiles() {
        EnumMap<AiConversationReasoningEffort,
                Map<AiConversationImageAspect, AiConversationImageProfile>> profiles =
                new EnumMap<>(AiConversationReasoningEffort.class);
        profiles.put(AiConversationReasoningEffort.LOW, googleTier(
                AiConversationImageQuality.LOW,
                AiConversationReasoningEffort.LOW,
                new int[][]{{512, 512}, {632, 424}, {424, 632}}));
        profiles.put(AiConversationReasoningEffort.MEDIUM, googleTier(
                AiConversationImageQuality.MEDIUM,
                AiConversationReasoningEffort.LOW,
                new int[][]{{1024, 1024}, {1264, 848}, {848, 1264}}));
        profiles.put(AiConversationReasoningEffort.HIGH, googleTier(
                AiConversationImageQuality.HIGH,
                AiConversationReasoningEffort.MEDIUM,
                new int[][]{{2048, 2048}, {2528, 1696}, {1696, 2528}}));
        profiles.put(AiConversationReasoningEffort.EXTRA_HIGH, googleTier(
                AiConversationImageQuality.ULTRA,
                AiConversationReasoningEffort.MEDIUM,
                new int[][]{{4096, 4096}, {5056, 3392}, {3392, 5056}}));
        return new ModelProfiles(
                Map.copyOf(profiles),
                List.of((short) 1, (short) 2, (short) 3, (short) 4));
    }

    private static Map<AiConversationImageAspect, AiConversationImageProfile>
            googleTier(
                    AiConversationImageQuality quality,
                    AiConversationReasoningEffort reasoningEffort,
                    int[][] dimensions) {
        AiConversationImageAspect[] aspects = AiConversationImageAspect.values();
        if (dimensions.length != aspects.length) {
            throw new IllegalStateException("Google image dimensions are incomplete.");
        }
        EnumMap<AiConversationImageAspect, AiConversationImageProfile> profiles =
                new EnumMap<>(AiConversationImageAspect.class);
        for (int index = 0; index < aspects.length; index++) {
            profiles.put(aspects[index], new AiConversationImageProfile(
                    quality,
                    dimensions[index][0],
                    dimensions[index][1],
                    reasoningEffort));
        }
        return Map.copyOf(profiles);
    }

    private static Map<AiConversationImageAspect, AiConversationImageProfile> tier(
            AiConversationImageQuality quality,
            AiConversationReasoningEffort reasoningEffort) {
        return tier(quality, reasoningEffort, 1);
    }

    private static Map<AiConversationImageAspect, AiConversationImageProfile> tier(
            AiConversationImageQuality quality,
            AiConversationReasoningEffort reasoningEffort,
            int dimensionScale) {
        EnumMap<AiConversationImageAspect, AiConversationImageProfile> profiles =
                new EnumMap<>(AiConversationImageAspect.class);
        for (AiConversationImageAspect aspect : AiConversationImageAspect.values()) {
            profiles.put(aspect, new AiConversationImageProfile(
                    quality,
                    Math.multiplyExact(aspect.width(), dimensionScale),
                    Math.multiplyExact(aspect.height(), dimensionScale),
                    reasoningEffort));
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
