package com.example.temperate.service.user.aiconversation.image.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证所有由管理员授予图片能力的协议兼容模型共享三档受控质量与标准尺寸。
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
    void doesNotUseModelNameAsRuntimeWhitelist() {
        assertTier("vendor-compatible-image-model", 1,
                AiConversationImageQuality.LOW,
                supportedSizes(),
                AiConversationReasoningEffort.LOW);
        assertThat(service.supports("vendor-compatible-image-model")).isTrue();
        assertThat(service.supports("  ")).isFalse();
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

    @Test
    void xaiExposesOnlyOneAndThreeAndRejectsLevelTwo() {
        assertThat(service.supportedLevels(AiModelProvider.XAI, "xai-image"))
                .containsExactly((short) 1, (short) 3);
        assertThat(service.required(
                        AiModelProvider.XAI,
                        "xai-image",
                        AiConversationReasoningEffort.HIGH,
                        AiConversationImageAspect.SQUARE)
                .size()).isEqualTo("2048x2048");
        assertThatThrownBy(() -> service.required(
                AiModelProvider.XAI,
                "xai-image",
                AiConversationReasoningEffort.MEDIUM,
                AiConversationImageAspect.SQUARE))
                .isInstanceOf(AiConversationException.class)
                .hasMessageContaining("1k 和 2k");
    }

    @Test
    void googleExposesFourResolutionTiers() {
        assertThat(service.supportedLevels(AiModelProvider.GOOGLE, "gemini-image"))
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4);
        assertThat(service.required(AiModelProvider.GOOGLE, "gemini-image",
                AiConversationReasoningEffort.LOW,
                AiConversationImageAspect.SQUARE).size()).isEqualTo("512x512");
        assertThat(service.required(AiModelProvider.GOOGLE, "gemini-image",
                AiConversationReasoningEffort.MEDIUM,
                AiConversationImageAspect.LANDSCAPE).size()).isEqualTo("1264x848");
        assertThat(service.required(AiModelProvider.GOOGLE, "gemini-image",
                AiConversationReasoningEffort.HIGH,
                AiConversationImageAspect.PORTRAIT).size()).isEqualTo("1696x2528");
        assertThat(service.required(AiModelProvider.GOOGLE, "gemini-image",
                AiConversationReasoningEffort.EXTRA_HIGH,
                AiConversationImageAspect.LANDSCAPE).size()).isEqualTo("5056x3392");
        assertThat(service.required(AiModelProvider.GOOGLE, "gemini-image",
                AiConversationReasoningEffort.EXTRA_HIGH,
                AiConversationImageAspect.SQUARE).quality())
                .isEqualTo(AiConversationImageQuality.ULTRA);
        assertThat(service.supports(AiModelProvider.ANTHROPIC, "claude"))
                .isFalse();
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
