package com.example.temperate.service.user.aiconversation.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证图片生成终态只保存 OSS URL 元数据，编码结果中绝不出现 Base64 或二进制字段。
 */
final class AiConversationPersistedGeneratedAttachmentCodecTest {

    private final AiConversationPersistedGeneratedAttachmentCodec codec =
            new AiConversationPersistedGeneratedAttachmentCodec(new ObjectMapper());

    @Test
    void roundTripsUrlMetadataWithoutBinaryPayload() {
        AiConversationAttachment attachment = AiConversationAttachment.available(
                "V1StGXR8_Z5jdHi6B-myT",
                "generated.webp",
                "image/webp",
                4096,
                AiConversationAttachmentCategory.IMAGE,
                "https://cdn.example/ai-temperate/conversations/image.webp");

        String json = codec.encode(List.of(attachment));

        assertThat(codec.decode(json)).containsExactly(attachment);
        assertThat(json).contains("\"schemaVersion\":2", "https://cdn.example/");
        assertThat(json).doesNotContain("base64", "b64_json", "bytes");
    }

    @Test
    void roundTripsTenGeneratedImagesAndReadsLegacySingleImageEnvelope()
            throws Exception {
        List<AiConversationAttachment> attachments = java.util.stream.IntStream
                .range(0, 10)
                .mapToObj(index -> AiConversationAttachment.available(
                        "image-attachment-" + index,
                        "generated-" + (index + 1) + ".webp",
                        "image/webp",
                        4096,
                        AiConversationAttachmentCategory.IMAGE,
                        "https://cdn.example/generated-" + index + ".webp"))
                .toList();

        assertThat(codec.decode(codec.encode(attachments)))
                .containsExactlyElementsOf(attachments);

        String legacy = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "schemaVersion", 1,
                "attachments", List.of(attachments.get(0))));
        assertThat(codec.decode(legacy)).containsExactly(attachments.get(0));
    }

    @Test
    void rejectsDataUrlsAtTheDatabaseEnvelopeBoundary() {
        AiConversationAttachment attachment = AiConversationAttachment.available(
                "V1StGXR8_Z5jdHi6B-myT",
                "generated.webp",
                "image/webp",
                4096,
                AiConversationAttachmentCategory.IMAGE,
                "data:image/webp;base64,YWJj");

        assertThatThrownBy(() -> codec.encode(List.of(attachment)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsMoreThanTenPersistedOutputs() {
        List<AiConversationAttachment> attachments = java.util.stream.IntStream
                .range(0, 11)
                .mapToObj(index -> AiConversationAttachment.available(
                        "image-attachment-" + index,
                        "generated-" + (index + 1) + ".webp",
                        "image/webp",
                        4096,
                        AiConversationAttachmentCategory.IMAGE,
                        "https://cdn.example/generated-" + index + ".webp"))
                .toList();

        assertThatThrownBy(() -> codec.encode(attachments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ten");
    }
}
