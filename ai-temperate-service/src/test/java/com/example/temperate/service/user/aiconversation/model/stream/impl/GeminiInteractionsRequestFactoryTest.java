package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Google Interactions 对话、联网搜索和图片请求不会混入其他供应商字段。
 */
final class GeminiInteractionsRequestFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsFourTextThinkingLevels() {
        GeminiInteractionsChatRequestFactory factory =
                new GeminiInteractionsChatRequestFactory(objectMapper);

        assertThinking(factory, AiConversationReasoningEffort.LOW, "minimal");
        assertThinking(factory, AiConversationReasoningEffort.MEDIUM, "low");
        assertThinking(factory, AiConversationReasoningEffort.HIGH, "medium");
        assertThinking(factory, AiConversationReasoningEffort.EXTRA_HIGH, "high");
    }

    @Test
    void mapsSearchModesToAutoAndAny() {
        GeminiInteractionsWebSearchRequestFactory factory =
                new GeminiInteractionsWebSearchRequestFactory(objectMapper);
        JsonNode auto = factory.create(textRequest(
                AiConversationReasoningEffort.MEDIUM,
                AiConversationWebSearchMode.AUTO));
        JsonNode required = factory.create(textRequest(
                AiConversationReasoningEffort.MEDIUM,
                AiConversationWebSearchMode.REQUIRED));

        assertThat(auto.path("tools").path(0).path("type").asText())
                .isEqualTo("google_search");
        assertThat(auto.path("tools").path(0).path("search_types").path(0).asText())
                .isEqualTo("web_search");
        assertThat(auto.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(required.path("tool_choice").asText()).isEqualTo("any");
        assertThat(required.toString())
                .doesNotContain("search_context_size", "reasoning", "output_config");
    }

    @Test
    void usesOfficialImageWireValuesAndOmitsTextThinkingLevel() {
        GeminiInteractionsImageRequestFactory factory =
                new GeminiInteractionsImageRequestFactory(objectMapper);
        JsonNode image = factory.create(imageRequest(AiConversationImageQuality.LOW));

        assertThat(image.path("response_format").path("type").asText())
                .isEqualTo("image");
        assertThat(image.path("response_format").path("image_size").asText())
                .isEqualTo("512");
        assertThat(image.path("response_format").path("mime_type").asText())
                .isEqualTo("image/jpeg");
        assertThat(image.path("response_format").path("aspect_ratio").asText())
                .isEqualTo("3:2");
        assertThat(image.path("generation_config").has("thinking_level")).isFalse();
    }

    private static void assertThinking(
            GeminiInteractionsChatRequestFactory factory,
            AiConversationReasoningEffort effort,
            String expected) {
        JsonNode root = factory.create(textRequest(
                effort, AiConversationWebSearchMode.OFF));
        assertThat(root.path("generation_config").path("thinking_level").asText())
                .isEqualTo(expected);
    }

    private static AiConversationStreamingRequest textRequest(
            AiConversationReasoningEffort effort,
            AiConversationWebSearchMode mode) {
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.GOOGLE,
                        "gemini-test",
                        256,
                        effort,
                        prompt()),
                mode);
    }

    private static AiConversationStreamingRequest imageRequest(
            AiConversationImageQuality quality) {
        AiConversationImageGenerationOptions options =
                new AiConversationImageGenerationOptions(
                        AiConversationImageGenerationOptions.CURRENT_PROFILE_VERSION,
                        AiConversationImageAspect.LANDSCAPE,
                        quality,
                        632,
                        424,
                        AiConversationReasoningEffort.LOW,
                        "webp",
                        90,
                        0,
                        AiConversationImageAction.GENERATE,
                        (short) 1);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.GOOGLE,
                        "gemini-image-test",
                        4096,
                        AiConversationReasoningEffort.LOW,
                        prompt(),
                        options,
                        (short) 0,
                        List.of()),
                AiConversationWebSearchMode.OFF);
    }

    private static AiConversationPromptSnapshot prompt() {
        return new AiConversationPromptSnapshot(
                "system", null, null, List.of(),
                new AiConversationContent("hello", List.of()),
                "generation", 5, false);
    }
}
