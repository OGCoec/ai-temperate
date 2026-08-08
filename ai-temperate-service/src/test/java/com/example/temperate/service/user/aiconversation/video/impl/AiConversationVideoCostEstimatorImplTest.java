package com.example.temperate.service.user.aiconversation.video.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoAspectRatio;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoPricingProfile;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 xAI 视频预扣只使用官方整数 ticks 价格，并对输入视频的不足一秒部分向上取整。
 */
final class AiConversationVideoCostEstimatorImplTest {

    private final AiConversationVideoCostEstimatorImpl estimator =
            new AiConversationVideoCostEstimatorImpl();

    @Test
    void estimatesTextToVideoAt480p() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        15,
                        AiConversationVideoResolution.P480,
                        AiConversationVideoAspectRatio.RATIO_16_9,
                        List.of(),
                        0L,
                        0,
                        0,
                        null);

        long ticks = estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P480, 800_000_000L),
                        100_000_000L,
                        0L));

        assertThat(ticks).isEqualTo(12_000_000_000L);
    }

    @Test
    void estimatesImageToVideoWithOfficialImageInputCost() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.IMAGE_TO_VIDEO,
                        10,
                        AiConversationVideoResolution.P1080,
                        AiConversationVideoAspectRatio.RATIO_16_9,
                        List.of("image-attachment"),
                        0L,
                        0,
                        0,
                        null);

        long ticks = estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P1080, 2_500_000_000L),
                        100_000_000L,
                        0L));

        assertThat(ticks).isEqualTo(25_100_000_000L);
    }

    @Test
    void estimatesReferenceImagesIndividually() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.REFERENCE_TO_VIDEO,
                        5,
                        AiConversationVideoResolution.P720,
                        AiConversationVideoAspectRatio.RATIO_1_1,
                        List.of("one", "two", "three"),
                        0L,
                        0,
                        0,
                        null);

        long ticks = estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P720, 1_400_000_000L),
                        100_000_000L,
                        0L));

        assertThat(ticks).isEqualTo(7_300_000_000L);
    }

    @Test
    void estimatesExtensionFromCompleteOutputAndInputDurations() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.VIDEO_EXTEND,
                        5,
                        AiConversationVideoResolution.P720,
                        null,
                        List.of("video-attachment"),
                        10_001L,
                        1280,
                        720,
                        "h264");

        long ticks = estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P720, 700_000_000L),
                        20_000_000L,
                        100_000_000L));

        // 输入 10.001 秒按 11 秒计费，最终输出按 11 + 5 秒估算。
        assertThat(ticks).isEqualTo(12_300_000_000L);
    }

    @Test
    void estimatesEditingFromInputDurationAndRejectsOverflow() {
        AiConversationVideoGenerationOptions options =
                new AiConversationVideoGenerationOptions(
                        AiConversationVideoMode.VIDEO_EDIT,
                        0,
                        AiConversationVideoResolution.P720,
                        null,
                        List.of("video-attachment"),
                        8_700L,
                        1280,
                        720,
                        "h264");

        long ticks = estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P720, 700_000_000L),
                        20_000_000L,
                        100_000_000L));

        assertThat(ticks).isEqualTo(7_200_000_000L);
        assertThatThrownBy(() -> estimator.estimateCostTicks(
                options,
                new AiConversationVideoPricingProfile(
                        Map.of(AiConversationVideoResolution.P720, Long.MAX_VALUE),
                        0L,
                        0L)))
                .isInstanceOf(ArithmeticException.class);
    }
}
