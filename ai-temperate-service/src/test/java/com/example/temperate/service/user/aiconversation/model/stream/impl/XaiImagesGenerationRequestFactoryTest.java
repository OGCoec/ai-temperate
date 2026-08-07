package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * 验证 xAI 图片生成和编辑请求按独立白名单构造同步 JSON，而不继承 OpenAI SSE 字段。
 */
final class XaiImagesGenerationRequestFactoryTest {

    private final XaiImagesGenerationRequestFactory factory =
            new XaiImagesGenerationRequestFactory(new ObjectMapper());

    @Test
    void buildsGenerationWithBase64AspectAndResolution() {
        JsonNode body = factory.create(request(
                AiConversationImageAction.GENERATE,
                AiConversationImageQuality.HIGH,
                List.of()));

        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("response_format").asText()).isEqualTo("b64_json");
        assertThat(body.path("aspect_ratio").asText()).isEqualTo("3:2");
        assertThat(body.path("resolution").asText()).isEqualTo("2k");
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("quality")).isFalse();
    }

    @Test
    void usesSingleImageAndRejectsMoreThanThreeEditReferences() {
        JsonNode body = factory.create(request(
                AiConversationImageAction.EDIT,
                AiConversationImageQuality.LOW,
                List.of("https://signed.example/input")));

        assertThat(body.path("image").path("url").asText())
                .isEqualTo("https://signed.example/input");
        assertThat(body.path("image").path("type").asText())
                .isEqualTo("image_url");
        assertThat(body.has("images")).isFalse();
        assertThatThrownBy(() -> factory.create(request(
                AiConversationImageAction.EDIT,
                AiConversationImageQuality.LOW,
                List.of("https://a", "https://b", "https://c", "https://d"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesImagesArrayForTwoOrThreeReferencesAndRejectsLevelTwo() {
        JsonNode two = factory.create(request(
                AiConversationImageAction.EDIT,
                AiConversationImageQuality.LOW,
                List.of("https://a", "https://b")));
        JsonNode three = factory.create(request(
                AiConversationImageAction.EDIT,
                AiConversationImageQuality.HIGH,
                List.of("https://a", "https://b", "https://c")));

        assertThat(two.path("images")).hasSize(2);
        assertThat(two.has("image")).isFalse();
        assertThat(three.path("images")).hasSize(3);
        assertThatThrownBy(() -> factory.create(request(
                AiConversationImageAction.GENERATE,
                AiConversationImageQuality.MEDIUM,
                List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level 2");
    }

    @Test
    void mapsAllSupportedAspectRatios() {
        assertThat(factory.create(request(
                AiConversationImageAspect.SQUARE)).path("aspect_ratio").asText())
                .isEqualTo("1:1");
        assertThat(factory.create(request(
                AiConversationImageAspect.LANDSCAPE)).path("aspect_ratio").asText())
                .isEqualTo("3:2");
        assertThat(factory.create(request(
                AiConversationImageAspect.PORTRAIT)).path("aspect_ratio").asText())
                .isEqualTo("2:3");
    }

    private static AiConversationStreamingRequest request(
            AiConversationImageAspect aspect) {
        return request(
                AiConversationImageAction.GENERATE,
                AiConversationImageQuality.LOW,
                List.of(),
                aspect);
    }

    private static AiConversationStreamingRequest request(
            AiConversationImageAction action,
            AiConversationImageQuality quality,
            List<String> imageUrls) {
        return request(action, quality, imageUrls,
                AiConversationImageAspect.LANDSCAPE);
    }

    private static AiConversationStreamingRequest request(
            AiConversationImageAction action,
            AiConversationImageQuality quality,
            List<String> imageUrls,
            AiConversationImageAspect aspect) {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system", null, null, List.of(),
                new AiConversationContent("draw", List.of()),
                "generation", 10, false);
        AiConversationImageGenerationOptions options =
                new AiConversationImageGenerationOptions(
                        "image-v1",
                        aspect,
                        quality,
                        1536,
                        1024,
                        quality == AiConversationImageQuality.HIGH
                                ? AiConversationReasoningEffort.HIGH
                                : AiConversationReasoningEffort.LOW,
                        "webp",
                        90,
                        0,
                        action,
                        (short) 1);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.XAI,
                        "grok-imagine-image-quality",
                        1024,
                        AiConversationReasoningEffort.MEDIUM,
                        prompt,
                        options,
                        (short) 0,
                        imageUrls),
                AiConversationWebSearchMode.OFF);
    }
}
