package com.example.temperate.service.user.aiconversation.image.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证两个图片模型只开放三档受控质量与标准尺寸，并拒绝客户端伪造更高档位。
 */
final class AiConversationImageProfileServiceImplTest {

    private final AiConversationImageProfileServiceImpl service =
            new AiConversationImageProfileServiceImpl();

    @Test
    void mapsOnlyThreeGptImage2Tiers() {
        List<String> supportedSizes = supportedSizes();
        assertTier("gpt-image-2", 1, AiConversationImageQuality.LOW,
                supportedSizes, AiConversationReasoningEffort.LOW);
        assertTier("gpt-image-2", 2, AiConversationImageQuality.MEDIUM,
                supportedSizes,
                AiConversationReasoningEffort.LOW);
        assertTier("gpt-image-2", 3, AiConversationImageQuality.HIGH,
                supportedSizes,
                AiConversationReasoningEffort.MEDIUM);

        assertUnsupportedHigherTiers("gpt-image-2");
    }

    @Test
    void mapsOnlyThreeGptImage15Tiers() {
        List<String> supportedSizes = supportedSizes();
        assertTier("gpt-image-1.5", 1, AiConversationImageQuality.LOW,
                supportedSizes,
                AiConversationReasoningEffort.LOW);
        assertTier("gpt-image-1.5", 2, AiConversationImageQuality.MEDIUM,
                supportedSizes,
                AiConversationReasoningEffort.LOW);
        assertTier("gpt-image-1.5", 3, AiConversationImageQuality.HIGH,
                supportedSizes,
                AiConversationReasoningEffort.MEDIUM);

        assertUnsupportedHigherTiers("gpt-image-1.5");
    }

    @Test
    void rejectsUnknownImageModel() {
        assertThatThrownBy(() -> service.required(
                "unknown-image-model",
                AiConversationReasoningEffort.LOW,
                AiConversationImageAspect.SQUARE))
                .isInstanceOf(AiConversationException.class)
                .hasMessageContaining("图片生成");
    }

    @Test
    void exposesStableCapabilitiesForModelCatalog() {
        assertThat(service.supportedLevels("gpt-image-2"))
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(service.supportedLevels("gpt-image-1.5"))
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(service.supportedAspects("gpt-image-2"))
                .containsExactly(AiConversationImageAspect.values());
    }

    private void assertTier(
            String model,
            int level,
            AiConversationImageQuality quality,
            List<String> sizes,
            AiConversationReasoningEffort compatibilityReasoning) {
        AiConversationImageAspect[] aspects = AiConversationImageAspect.values();
        for (int index = 0; index < aspects.length; index++) {
            AiConversationImageProfile profile = service.required(
                    model,
                    AiConversationReasoningEffort.fromLevel((short) level),
                    aspects[index]);
            assertThat(profile.quality()).isEqualTo(quality);
            assertThat(profile.size()).isEqualTo(sizes.get(index));
            assertThat(profile.reasoningEffort()).isEqualTo(compatibilityReasoning);
        }
    }

    private void assertUnsupportedHigherTiers(String model) {
        for (AiConversationReasoningEffort effort : List.of(
                AiConversationReasoningEffort.EXTRA_HIGH,
                AiConversationReasoningEffort.ULTRA)) {
            assertThatThrownBy(() -> service.required(
                    model, effort, AiConversationImageAspect.SQUARE))
                    .isInstanceOf(AiConversationException.class)
                    .hasMessageContaining("三档");
        }
    }

    private static List<String> supportedSizes() {
        return List.of("1024x1024", "1536x1024", "1024x1536");
    }
}
