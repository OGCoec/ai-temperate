package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Images Generation 请求只发送服务端白名单参数，并按画幅正规化旧快照尺寸。
 */
final class OpenAiImagesGenerationRequestFactoryTest {

    private final OpenAiImagesGenerationRequestFactory factory =
            new OpenAiImagesGenerationRequestFactory(new ObjectMapper());

    @Test
    void buildsImagesGenerationRequestWithoutResponsesFields() {
        JsonNode body = factory.create(request());

        assertThat(body.path("model").asText()).isEqualTo("gpt-image-2");
        assertThat(body.path("prompt").asText()).isEqualTo("draw a quiet lake");
        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("quality").asText()).isEqualTo("high");
        assertThat(body.path("size").asText()).isEqualTo("1536x1024");
        assertThat(body.path("output_format").asText()).isEqualTo("webp");
        assertThat(body.path("output_compression").asInt()).isEqualTo(90);
        assertThat(body.path("partial_images").asInt()).isEqualTo(3);
        assertThat(body.has("input")).isFalse();
        assertThat(body.has("instructions")).isFalse();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.has("reasoning")).isFalse();
        assertThat(body.has("store")).isFalse();
        assertThat(body.has("max_output_tokens")).isFalse();
        assertThat(body.toString()).doesNotContain("b64_json", "data:image");
    }

    @Test
    void buildsJsonEditRequestWithSignedReferencesForOneChildStream() {
        AiConversationStreamingRequest request = request(
                AiConversationImageAction.EDIT,
                List.of("https://signed.example/input-1", "https://signed.example/input-2"),
                (short) 3);

        JsonNode body = factory.create(request);

        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("images").isArray()).isTrue();
        assertThat(body.path("images")).hasSize(2);
        assertThat(body.path("images").get(0).path("image_url").asText())
                .isEqualTo("https://signed.example/input-1");
        assertThat(body.toString()).doesNotContain("base64", "b64_json");
    }

    private static AiConversationStreamingRequest request() {
        return request(AiConversationImageAction.GENERATE, List.of(), (short) 0);
    }

    private static AiConversationStreamingRequest request(
            AiConversationImageAction action,
            List<String> imageUrls,
            short outputIndex) {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system",
                null,
                null,
                List.of(),
                new AiConversationContent("draw a quiet lake", List.of()),
                "generation",
                10,
                false);
        AiConversationImageGenerationOptions image =
                new AiConversationImageGenerationOptions(
                        "image-v1",
                        AiConversationImageAspect.LANDSCAPE,
                        AiConversationImageQuality.HIGH,
                        2560,
                        1440,
                        AiConversationReasoningEffort.HIGH,
                        "webp",
                        90,
                        0,
                        action,
                        (short) 4);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        "gpt-image-2",
                        1024,
                        AiConversationReasoningEffort.HIGH,
                        prompt,
                        image,
                        outputIndex,
                        imageUrls),
                AiConversationWebSearchMode.OFF);
    }
}
