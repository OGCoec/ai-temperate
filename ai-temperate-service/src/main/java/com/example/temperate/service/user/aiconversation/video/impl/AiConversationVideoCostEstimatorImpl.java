package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoCostEstimator;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoPricingProfile;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 按 xAI 官方输出秒价和媒体输入价计算预扣，所有乘加都使用可检测溢出的整数运算。
 */
@Service
public final class AiConversationVideoCostEstimatorImpl
        implements AiConversationVideoCostEstimator {

    @Override
    public long estimateCostTicks(
            AiConversationVideoGenerationOptions options,
            AiConversationVideoPricingProfile pricing) {
        Objects.requireNonNull(options);
        Objects.requireNonNull(pricing);
        long inputVideoSeconds = roundedUpSeconds(
                options.inputVideoDurationMillis());
        long outputSeconds = switch (options.mode()) {
            case VIDEO_EDIT -> inputVideoSeconds;
            case VIDEO_EXTEND -> Math.addExact(
                    inputVideoSeconds, options.durationSeconds());
            default -> options.durationSeconds();
        };
        long outputCost = Math.multiplyExact(
                outputSeconds,
                pricing.requiredOutputCostTicksPerSecond(options.resolution()));
        long imageInputCost = Math.multiplyExact(
                options.inputImageCount(),
                pricing.imageInputCostTicksEach());
        long videoInputCost = options.mode() == AiConversationVideoMode.VIDEO_EDIT
                        || options.mode() == AiConversationVideoMode.VIDEO_EXTEND
                ? Math.multiplyExact(
                        inputVideoSeconds,
                        pricing.videoInputCostTicksPerSecond())
                : 0L;
        return Math.addExact(
                Math.addExact(outputCost, imageInputCost),
                videoInputCost);
    }

    private static long roundedUpSeconds(long durationMillis) {
        if (durationMillis <= 0L) {
            return 0L;
        }
        return Math.floorDiv(Math.addExact(durationMillis, 999L), 1_000L);
    }
}
