package com.example.temperate.service.user.aiconversation.video.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import org.junit.jupiter.api.Test;

/**
 * 验证官方 xAI 视频模型能力不会被自动互换，并限制参考图、编辑和延长的最高画质。
 */
final class AiConversationVideoProfileServiceImplTest {

    private final AiConversationVideoProfileServiceImpl service =
            new AiConversationVideoProfileServiceImpl(
                    AiConversationVideoGenerationProperties.officialDefaults());

    @Test
    void exposesGenerationModesForVersion15() {
        var profile = service.required(
                AiModelProvider.XAI,
                "grok-imagine-video-1.5",
                AiConversationVideoMode.IMAGE_TO_VIDEO,
                AiConversationVideoResolution.P1080);

        assertThat(profile.pricing().requiredOutputCostTicksPerSecond(
                AiConversationVideoResolution.P1080))
                .isEqualTo(2_500_000_000L);
        assertThat(service.supportedModes(
                AiModelProvider.XAI,
                "grok-imagine-video-1.5"))
                .containsExactly(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        AiConversationVideoMode.IMAGE_TO_VIDEO,
                        AiConversationVideoMode.REFERENCE_TO_VIDEO);
    }

    @Test
    void rejects1080pReferenceGeneration() {
        assertThatThrownBy(() -> service.required(
                AiModelProvider.XAI,
                "grok-imagine-video-1.5",
                AiConversationVideoMode.REFERENCE_TO_VIDEO,
                AiConversationVideoResolution.P1080))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesEditingOnlyForLegacyModel() {
        assertThat(service.supportedModes(
                AiModelProvider.XAI,
                "grok-imagine-video"))
                .containsExactly(
                        AiConversationVideoMode.VIDEO_EDIT,
                        AiConversationVideoMode.VIDEO_EXTEND);
        assertThatThrownBy(() -> service.required(
                AiModelProvider.XAI,
                "grok-imagine-video-1.5",
                AiConversationVideoMode.VIDEO_EDIT,
                AiConversationVideoResolution.P720))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
