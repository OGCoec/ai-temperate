package com.example.temperate.service.user.aiconversation.image;

import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.Objects;

/**
 * 表示服务端从产品档位冻结出的图片质量、标准尺寸和兼容旧异步快照的档位元数据。
 *
 * <p>推理强度不发送给 Images Generation 上游，只用于保持现有 Generation 输入信封可恢复。</p>
 */
public record AiConversationImageProfile(
        AiConversationImageQuality quality,
        int width,
        int height,
        AiConversationReasoningEffort reasoningEffort) {

    private static final long MAXIMUM_PIXELS = 8_294_400L;

    public AiConversationImageProfile {
        quality = Objects.requireNonNull(quality);
        reasoningEffort = Objects.requireNonNull(reasoningEffort);
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0
                || width % 16 != 0 || height % 16 != 0
                || pixels > MAXIMUM_PIXELS
                || Math.max(width, height) > 3L * Math.min(width, height)) {
            throw new IllegalArgumentException("Image dimensions violate the supported boundary.");
        }
    }

    public String size() {
        return width + "x" + height;
    }
}
