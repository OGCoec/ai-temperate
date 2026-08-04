package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Images Generation 事件被映射为完整预览、最终图和权威 usage，并拒绝非法图片数据。
 */
final class OpenAiImagesGenerationEventMapperTest {

    private static final byte[] WEBP_IMAGE = new byte[] {
            'R', 'I', 'F', 'F', 0x04, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
    private static final byte[] PNG_IMAGE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};
    private final OpenAiImagesGenerationEventMapper mapper =
            new OpenAiImagesGenerationEventMapper(new ObjectMapper(), 1024);

    @Test
    void mapsPartialImageAsOneCompletePreview() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.partial_image",
                "{\"type\":\"image_generation.partial_image\","
                        + "\"partial_image_index\":1,"
                        + "\"b64_json\":\"" + base64 + "\","
                        + "\"size\":\"1536x1024\",\"quality\":\"high\","
                        + "\"output_format\":\"webp\"}");

        List<AiConversationModelEvent> mapped = mapper.map(event, options());

        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        assertThat(image.value().phase())
                .isEqualTo(AiConversationGeneratedImagePhase.PARTIAL);
        assertThat(image.value().index()).isEqualTo(1);
        assertThat(image.value().bytes()).containsExactly(WEBP_IMAGE);
        assertThat(image.value().contentType()).isEqualTo("image/webp");
        assertThat(image.value().width()).isEqualTo(1536);
        assertThat(image.value().height()).isEqualTo(1024);
    }

    @Test
    void mapsFinalImageAndUsageFromCompletedResponse() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\",\"id\":\"img_123\","
                        + "\"b64_json\":\"" + base64 + "\","
                        + "\"size\":\"1536x1024\",\"quality\":\"high\","
                        + "\"output_format\":\"webp\","
                        + "\"usage\":{\"total_tokens\":34,"
                        + "\"input_tokens\":13,\"output_tokens\":21,"
                        + "\"input_tokens_details\":{"
                        + "\"text_tokens\":13,\"image_tokens\":0}}}");

        List<AiConversationModelEvent> mapped = mapper.map(event, options());

        assertThat(mapped).hasSize(2);
        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        AiConversationModelEvent.Chunk terminal =
                (AiConversationModelEvent.Chunk) mapped.get(1);
        assertThat(image.value().phase())
                .isEqualTo(AiConversationGeneratedImagePhase.FINAL);
        assertThat(image.value().bytes()).containsExactly(WEBP_IMAGE);
        assertThat(image.value().contentType()).isEqualTo("image/webp");
        assertThat(terminal.value().usage().promptTokens()).isEqualTo(13);
        assertThat(terminal.value().usage().cachedPromptTokens()).isZero();
        assertThat(terminal.value().usage().completionTokens()).isEqualTo(21);
        assertThat(terminal.value().usage().reasoningTokens()).isZero();
        assertThat(terminal.value().upstreamRequestId()).isEqualTo("img_123");
    }

    @Test
    void usesPngMetadataWhenAWebpRequestReturnsPngBytes() {
        String base64 = Base64.getEncoder().encodeToString(PNG_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"" + base64 + "\","
                        + "\"output_format\":\"webp\"}");

        List<AiConversationModelEvent> mapped = mapper.map(event, options());

        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        assertThat(image.value().contentType()).isEqualTo("image/png");
        assertThat(image.value().bytes()).containsExactly(PNG_IMAGE);
    }

    @Test
    void rejectsAnUnsupportedDecodedImageFormat() {
        String base64 = Base64.getEncoder().encodeToString(
                new byte[] {'G', 'I', 'F', '8', '9', 'a'});
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"" + base64 + "\"}");

        assertThatThrownBy(() -> mapper.map(event, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format");
    }

    @Test
    void rejectsOutOfRangePartialImageIndex() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.partial_image",
                "{\"type\":\"image_generation.partial_image\","
                        + "\"partial_image_index\":3,"
                        + "\"b64_json\":\"" + base64 + "\"}");

        assertThatThrownBy(() -> mapper.map(event, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index");
    }

    @Test
    void rejectsMalformedOrOversizedBase64() {
        OpenAiResponsesSseEvent malformed = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"not-base64!\"}");
        String oversized = Base64.getEncoder().encodeToString(new byte[1025]);
        OpenAiResponsesSseEvent tooLarge = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"" + oversized + "\"}");

        assertThatThrownBy(() -> mapper.map(malformed, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
        assertThatThrownBy(() -> mapper.map(tooLarge, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void mapsErrorAndIgnoresDoneSentinel() {
        OpenAiResponsesSseEvent error = new OpenAiResponsesSseEvent(
                "error", "{\"type\":\"error\"}");
        OpenAiResponsesSseEvent done = new OpenAiResponsesSseEvent(
                "message", "[DONE]");

        assertThat(mapper.map(error, options()))
                .singleElement()
                .isInstanceOf(AiConversationModelEvent.Failure.class);
        assertThat(mapper.map(done, options())).isEmpty();
    }

    private static AiConversationImageGenerationOptions options() {
        return new AiConversationImageGenerationOptions(
                "image-v1",
                AiConversationImageAspect.LANDSCAPE,
                AiConversationImageQuality.HIGH,
                2560,
                1440,
                AiConversationReasoningEffort.HIGH,
                "webp",
                90,
                0);
    }
}
