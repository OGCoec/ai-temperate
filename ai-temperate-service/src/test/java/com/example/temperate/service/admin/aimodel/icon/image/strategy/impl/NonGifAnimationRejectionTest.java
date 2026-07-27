package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import org.junit.jupiter.api.Test;

/**
 * 验证动画能力只由受限 GIF 提供，PNG 和 WebP 的动画容器标记会被拒绝。
 */
final class NonGifAnimationRejectionTest {

    @Test
    void rejectsApngAnimationControlChunk() {
        byte[] apngHeader = {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0, 0, 0, 0, 'a', 'c', 'T', 'L', 0, 0, 0, 0
        };

        assertUnsafe(() -> new PngAiModelIconImageValidationStrategy()
                .validateContainerBeforeDecode(apngHeader));
    }

    @Test
    void rejectsAnimatedWebpChunk() {
        byte[] animatedWebpHeader = {
                'R', 'I', 'F', 'F', 12, 0, 0, 0, 'W', 'E', 'B', 'P',
                'A', 'N', 'I', 'M', 0, 0, 0, 0
        };

        assertUnsafe(() -> new WebpAiModelIconImageValidationStrategy()
                .validateContainerBeforeDecode(animatedWebpHeader));
    }

    private static void assertUnsafe(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }
}
