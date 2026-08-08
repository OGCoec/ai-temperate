package com.example.temperate.service.user.aiconversation.image;

import java.util.Objects;

/**
 * 承载 Web 边界校验后的图片画幅和输出数量意图，不允许客户端注入原始上游质量或尺寸。
 */
public record AiConversationImageGenerationRequest(
        AiConversationImageAspect aspect,
        short outputCount) {

    public static final short MINIMUM_OUTPUT_COUNT = 1;
    public static final short MAXIMUM_OUTPUT_COUNT = 10;

    public AiConversationImageGenerationRequest {
        aspect = Objects.requireNonNull(aspect);
        if (outputCount < MINIMUM_OUTPUT_COUNT
                || outputCount > MAXIMUM_OUTPUT_COUNT) {
            throw new IllegalArgumentException(
                    "Image output count must be between 1 and 10.");
        }
    }

    public AiConversationImageGenerationRequest(
            AiConversationImageAspect aspect) {
        this(aspect, (short) 1);
    }
}
