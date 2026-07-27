package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import org.junit.jupiter.api.Test;

/**
 * 验证 GIF、ICO 和静态 AVIF 的帧数或条目数边界使用危险内容错误码。
 */
final class ImageIoFrameBoundaryTest {

    @Test
    void acceptsGifAndIcoUpperBounds() {
        assertThatCode(() ->
                AbstractImageIoAiModelIconImageValidationStrategy.validateFrameCount(
                        GifAiModelIconImageValidationStrategy.MAX_FRAMES,
                        GifAiModelIconImageValidationStrategy.MAX_FRAMES,
                        false))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                AbstractImageIoAiModelIconImageValidationStrategy.validateFrameCount(
                        IcoAiModelIconImageValidationStrategy.MAX_ENTRIES,
                        IcoAiModelIconImageValidationStrategy.MAX_ENTRIES,
                        false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsGifIcoOverflowAndAnimatedAvif() {
        assertUnsafe(
                GifAiModelIconImageValidationStrategy.MAX_FRAMES + 1,
                GifAiModelIconImageValidationStrategy.MAX_FRAMES,
                false);
        assertUnsafe(
                IcoAiModelIconImageValidationStrategy.MAX_ENTRIES + 1,
                IcoAiModelIconImageValidationStrategy.MAX_ENTRIES,
                false);
        assertUnsafe(2, 1, true);
    }

    @Test
    void rejectsOversizedFrameAndCumulativePixels() {
        assertThatThrownBy(() ->
                AbstractImageIoAiModelIconImageValidationStrategy.accumulatePixels(
                        0,
                        4097,
                        1))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
        assertThatThrownBy(() ->
                AbstractImageIoAiModelIconImageValidationStrategy.accumulatePixels(
                        (4096L * 4096L) - 1,
                        1,
                        2))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    private static void assertUnsafe(
            int frameCount,
            int maximumFrames,
            boolean requireSingleFrame) {
        assertThatThrownBy(() ->
                AbstractImageIoAiModelIconImageValidationStrategy.validateFrameCount(
                        frameCount,
                        maximumFrames,
                        requireSingleFrame))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }
}
