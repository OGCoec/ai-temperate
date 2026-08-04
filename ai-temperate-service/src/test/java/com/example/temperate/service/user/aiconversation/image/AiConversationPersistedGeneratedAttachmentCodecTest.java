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
        assertThat(json).contains("\"schemaVersion\":1", "https://cdn.example/");
        assertThat(json).doesNotContain("base64", "b64_json", "bytes");
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
}
