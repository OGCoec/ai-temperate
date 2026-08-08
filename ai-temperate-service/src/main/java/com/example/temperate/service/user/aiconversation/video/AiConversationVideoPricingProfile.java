package com.example.temperate.service.user.aiconversation.video;

import java.util.EnumMap;
import java.util.Map;

/**
 * 保存某个 xAI 视频模型的官方整数 ticks 价格，不使用浮点美元或项目 Token 倍率。
 */
public record AiConversationVideoPricingProfile(
        Map<AiConversationVideoResolution, Long> outputCostTicksPerSecond,
        long imageInputCostTicksEach,
        long videoInputCostTicksPerSecond) {

    public AiConversationVideoPricingProfile {
        EnumMap<AiConversationVideoResolution, Long> copied =
                new EnumMap<>(AiConversationVideoResolution.class);
        if (outputCostTicksPerSecond != null) {
            copied.putAll(outputCostTicksPerSecond);
        }
        if (copied.isEmpty()
                || copied.values().stream().anyMatch(value -> value == null || value <= 0L)
                || imageInputCostTicksEach < 0L
                || videoInputCostTicksPerSecond < 0L) {
            throw new IllegalArgumentException("Video pricing profile is invalid.");
        }
        outputCostTicksPerSecond = Map.copyOf(copied);
    }

    public long requiredOutputCostTicksPerSecond(
            AiConversationVideoResolution resolution) {
        Long ticks = outputCostTicksPerSecond.get(resolution);
        if (ticks == null) {
            throw new IllegalArgumentException(
                    "Video resolution has no configured price.");
        }
        return ticks;
    }
}
