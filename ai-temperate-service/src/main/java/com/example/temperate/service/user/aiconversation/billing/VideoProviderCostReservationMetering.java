package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import java.util.Objects;

/**
 * 冻结视频请求的官方单价、输入规模和预计供应商成本，保证排队期间配置变化不会改写历史预扣依据。
 */
public record VideoProviderCostReservationMetering(
        AiConversationVideoMode mode,
        AiConversationVideoResolution resolution,
        int requestedDurationSeconds,
        int inputImageCount,
        long inputVideoDurationMillis,
        long outputCostTicksPerSecond,
        long imageInputCostTicksEach,
        long videoInputCostTicksPerSecond,
        long estimatedProviderCostTicks)
        implements AiConversationReservationMetering {

    public VideoProviderCostReservationMetering {
        mode = Objects.requireNonNull(mode);
        resolution = Objects.requireNonNull(resolution);
        if (requestedDurationSeconds < 0
                || inputImageCount < 0
                || inputVideoDurationMillis < 0L
                || outputCostTicksPerSecond < 0L
                || imageInputCostTicksEach < 0L
                || videoInputCostTicksPerSecond < 0L
                || estimatedProviderCostTicks < 0L) {
            throw new IllegalArgumentException(
                    "Video provider-cost reservation values must be non-negative.");
        }
    }

    @Override
    public AiConversationMeteringBasis basis() {
        return AiConversationMeteringBasis.PROVIDER_COST_TICKS;
    }
}
