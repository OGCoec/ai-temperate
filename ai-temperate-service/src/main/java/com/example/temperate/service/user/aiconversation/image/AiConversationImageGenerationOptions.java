package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.Objects;

/**
 * 冻结一次图片 Generation 的业务档位和输出参数，并为旧异步快照保留可解码字段。
 *
 * <p>真正发送上游时仍会按当前画幅白名单正规化尺寸和预览数量，避免旧快照绕过 Images API 边界。</p>
 */
public record AiConversationImageGenerationOptions(
        String profileVersion,
        AiConversationImageAspect aspect,
        AiConversationImageQuality quality,
        int width,
        int height,
        AiConversationReasoningEffort reasoningEffort,
        String outputFormat,
        int outputCompression,
        int partialImages,
        AiConversationImageAction action,
        short outputCount) {

    public static final String CURRENT_PROFILE_VERSION = "image-v2";
    public static final int MAXIMUM_PARTIAL_IMAGES = 3;

    public AiConversationImageGenerationOptions {
        profileVersion = requireText(profileVersion, "profileVersion");
        aspect = Objects.requireNonNull(aspect);
        quality = Objects.requireNonNull(quality);
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
        action = Objects.requireNonNull(action);
        outputFormat = requireText(outputFormat, "outputFormat");
        if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0) {
            throw new IllegalArgumentException("Image dimensions must be positive multiples of 8.");
        }
        if (!"webp".equals(outputFormat)
                || outputCompression < 1 || outputCompression > 100
                || partialImages < 0 || partialImages > MAXIMUM_PARTIAL_IMAGES
                || outputCount < AiConversationImageGenerationRequest.MINIMUM_OUTPUT_COUNT
                || outputCount > AiConversationImageGenerationRequest.MAXIMUM_OUTPUT_COUNT) {
            throw new IllegalArgumentException("Image output options are invalid.");
        }
    }

    /**
     * 保留 v2 快照和既有调用点的构造契约；旧数据固定解释为单张文字生成。
     */
    public AiConversationImageGenerationOptions(
            String profileVersion,
            AiConversationImageAspect aspect,
            AiConversationImageQuality quality,
            int width,
            int height,
            AiConversationReasoningEffort reasoningEffort,
            String outputFormat,
            int outputCompression,
            int partialImages) {
        this(profileVersion, aspect, quality, width, height, reasoningEffort,
                outputFormat, outputCompression, partialImages,
                AiConversationImageAction.GENERATE, (short) 1);
    }

    public static AiConversationImageGenerationOptions from(
            AiConversationImageAspect aspect,
            AiConversationImageProfile profile) {
        Objects.requireNonNull(profile);
        return new AiConversationImageGenerationOptions(
                CURRENT_PROFILE_VERSION,
                aspect,
                profile.quality(),
                profile.width(),
                profile.height(),
                profile.reasoningEffort(),
                "webp",
                90,
                MAXIMUM_PARTIAL_IMAGES,
                AiConversationImageAction.GENERATE,
                (short) 1);
    }

    public static AiConversationImageGenerationOptions from(
            AiConversationImageAspect aspect,
            AiConversationImageProfile profile,
            AiConversationImageAction action,
            short outputCount) {
        Objects.requireNonNull(profile);
        return new AiConversationImageGenerationOptions(
                CURRENT_PROFILE_VERSION,
                aspect,
                profile.quality(),
                profile.width(),
                profile.height(),
                profile.reasoningEffort(),
                "webp",
                90,
                MAXIMUM_PARTIAL_IMAGES,
                action,
                outputCount);
    }

    public String size() {
        return width + "x" + height;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
