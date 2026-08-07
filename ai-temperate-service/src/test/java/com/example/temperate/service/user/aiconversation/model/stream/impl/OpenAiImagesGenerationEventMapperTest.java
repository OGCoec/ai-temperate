package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
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

        List<AiConversationModelEvent> mapped = mapper.map(
                event, options(), (short) 2);

        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        assertThat(image.value().phase())
                .isEqualTo(AiConversationGeneratedImagePhase.PARTIAL);
        assertThat(image.value().outputIndex()).isEqualTo((short) 2);
        assertThat(image.value().partialImageIndex()).isEqualTo((short) 1);
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
                        + "\"text_tokens\":13,\"image_tokens\":0,"
                        + "\"cached_tokens\":3},"
                        + "\"output_tokens_details\":{"
                        + "\"reasoning_tokens\":5}}}");

        List<AiConversationModelEvent> mapped = mapper.map(
                event, options(), (short) 2);

        assertThat(mapped).hasSize(2);
        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        AiConversationModelEvent.ImageUsage terminal =
                (AiConversationModelEvent.ImageUsage) mapped.get(1);
        assertThat(image.value().phase())
                .isEqualTo(AiConversationGeneratedImagePhase.FINAL);
        assertThat(image.value().partialImageIndex()).isNull();
        assertThat(image.value().bytes()).containsExactly(WEBP_IMAGE);
        assertThat(image.value().contentType()).isEqualTo("image/webp");
        assertThat(terminal.outputIndex()).isEqualTo((short) 2);
        assertThat(terminal.usage()).isInstanceOf(AiConversationUsage.class);
        AiConversationUsage usage = (AiConversationUsage) terminal.usage();
        assertThat(usage.promptTokens()).isEqualTo(13);
        assertThat(usage.cachedPromptTokens()).isEqualTo(3);
        assertThat(usage.completionTokens()).isEqualTo(21);
        assertThat(usage.reasoningTokens()).isEqualTo(5);
        assertThat(terminal.upstreamRequestId()).isEqualTo("img_123");
    }

    @Test
    void usesPngMetadataWhenAWebpRequestReturnsPngBytes() {
        String base64 = Base64.getEncoder().encodeToString(PNG_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"" + base64 + "\","
                        + "\"output_format\":\"webp\"}");

        List<AiConversationModelEvent> mapped = mapper.map(
                event, options(), (short) 0);

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

        assertThatThrownBy(() -> mapper.map(event, options(), (short) 0))
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

        assertThatThrownBy(() -> mapper.map(event, options(), (short) 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index");
    }

    @Test
    void rejectsFractionalPartialIndexAndMalformedUsage() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent fractionalIndex = new OpenAiResponsesSseEvent(
                "image_generation.partial_image",
                "{\"type\":\"image_generation.partial_image\","
                        + "\"partial_image_index\":1.5,"
                        + "\"b64_json\":\"" + base64 + "\"}");
        OpenAiResponsesSseEvent malformedUsage = new OpenAiResponsesSseEvent(
                "image_generation.completed",
                "{\"type\":\"image_generation.completed\","
                        + "\"b64_json\":\"" + base64 + "\","
                        + "\"usage\":{\"input_tokens\":1.5,"
                        + "\"output_tokens\":2}}");

        assertThatThrownBy(() -> mapper.map(
                fractionalIndex, options(), (short) 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> mapper.map(
                malformedUsage, options(), (short) 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usage");
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

        assertThatThrownBy(() -> mapper.map(malformed, options(), (short) 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
        assertThatThrownBy(() -> mapper.map(tooLarge, options(), (short) 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void mapsErrorAndIgnoresDoneSentinel() {
        OpenAiResponsesSseEvent error = new OpenAiResponsesSseEvent(
                "error", "{\"type\":\"error\"}");
        OpenAiResponsesSseEvent done = new OpenAiResponsesSseEvent(
                "message", "[DONE]");

        assertThat(mapper.map(error, options(), (short) 0))
                .singleElement()
                .isInstanceOf(AiConversationModelEvent.Failure.class);
        assertThat(mapper.map(done, options(), (short) 0)).isEmpty();
    }

    @Test
    void mapsEditProtocolEventsToTheSameDualIndexContract() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_edit.partial_image",
                "{\"type\":\"image_edit.partial_image\","
                        + "\"partial_image_index\":0,"
                        + "\"b64_json\":\"" + base64 + "\"}");

        List<AiConversationModelEvent> mapped = mapper.map(
                event, options(), (short) 7);

        AiConversationModelEvent.Image image =
                (AiConversationModelEvent.Image) mapped.get(0);
        assertThat(image.value().outputIndex()).isEqualTo((short) 7);
        assertThat(image.value().partialImageIndex()).isZero();
    }

    @Test
    void describesMappedPartialWithoutRetainingEncodedPayload() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "image_generation.partial_image",
                "{\"type\":\"image_generation.partial_image\","
                        + "\"partial_image_index\":1,"
                        + "\"b64_json\":\"" + base64 + "\"}");

        OpenAiImagesGenerationMappingResult result = mapper.mapDetailed(
                event, options(), (short) 2);

        assertThat(result.upstreamEventName())
                .isEqualTo("image_generation.partial_image");
        assertThat(result.upstreamJsonType())
                .isEqualTo("image_generation.partial_image");
        assertThat(result.partialImageIndex()).isEqualTo(1);
        assertThat(result.imagePayloadField()).isEqualTo("b64_json");
        assertThat(result.encodedImageCharacters()).isEqualTo(base64.length());
        assertThat(result.outcome())
                .isEqualTo(OpenAiImagesGenerationMappingOutcome.PARTIAL);
        assertThat(result.events()).hasSize(1);
    }

    @Test
    void describesUnsupportedResponsesPartialWithoutCopyingBase64() {
        String base64 = Base64.getEncoder().encodeToString(WEBP_IMAGE);
        OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
                "message",
                "{\"type\":\"response.image_generation_call.partial_image\","
                        + "\"partial_image_index\":0,"
                        + "\"partial_image_b64\":\"" + base64 + "\"}");

        OpenAiImagesGenerationMappingResult result = mapper.mapDetailed(
                event, options(), (short) 0);

        assertThat(result.upstreamJsonType())
                .isEqualTo("response.image_generation_call.partial_image");
        assertThat(result.partialImageIndex()).isZero();
        assertThat(result.imagePayloadField()).isEqualTo("partial_image_b64");
        assertThat(result.encodedImageCharacters()).isEqualTo(base64.length());
        assertThat(result.outcome())
                .isEqualTo(OpenAiImagesGenerationMappingOutcome.IGNORED);
        assertThat(result.events()).isEmpty();
        assertThat(result.toString()).doesNotContain(base64);
    }

    @Test
    void describesDoneSentinelWithoutParsingJson() {
        OpenAiImagesGenerationMappingResult result = mapper.mapDetailed(
                new OpenAiResponsesSseEvent("message", "[DONE]"),
                options(),
                (short) 0);

        assertThat(result.outcome())
                .isEqualTo(OpenAiImagesGenerationMappingOutcome.DONE);
        assertThat(result.events()).isEmpty();
        assertThat(result.encodedImageCharacters()).isZero();
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
