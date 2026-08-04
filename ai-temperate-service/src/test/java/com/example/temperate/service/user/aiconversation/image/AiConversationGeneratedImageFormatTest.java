package com.example.temperate.service.user.aiconversation.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证生成图片只能根据真实字节签名确定格式，并为 OSS 元数据提供规范的 MIME 与文件后缀。
 */
final class AiConversationGeneratedImageFormatTest {

    @Test
    void detectsPngFromSignatureAndProvidesCanonicalMetadata() {
        AiConversationGeneratedImageFormat format =
                AiConversationGeneratedImageFormat.detect(
                        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47,
                                0x0D, 0x0A, 0x1A, 0x0A, 0x01});

        assertThat(format).isEqualTo(AiConversationGeneratedImageFormat.PNG);
        assertThat(format.contentType()).isEqualTo("image/png");
        assertThat(format.generatedFileName()).isEqualTo("generated.png");
    }

    @Test
    void detectsJpegAndWebpFromSignatures() {
        AiConversationGeneratedImageFormat jpeg =
                AiConversationGeneratedImageFormat.detect(
                        new byte[] {(byte) 0xFF, (byte) 0xD8,
                                (byte) 0xFF, (byte) 0xE0});
        AiConversationGeneratedImageFormat webp =
                AiConversationGeneratedImageFormat.detect(
                        new byte[] {'R', 'I', 'F', 'F', 0x04, 0x00, 0x00, 0x00,
                                'W', 'E', 'B', 'P'});

        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.generatedFileName()).isEqualTo("generated.jpg");
        assertThat(webp.contentType()).isEqualTo("image/webp");
        assertThat(webp.generatedFileName()).isEqualTo("generated.webp");
    }

    @Test
    void resolvesCanonicalGeneratedFileNameFromContentType() {
        assertThat(AiConversationGeneratedImageFormat
                .fromContentType("image/png").generatedFileName())
                .isEqualTo("generated.png");
        assertThat(AiConversationGeneratedImageFormat
                .fromContentType("image/jpeg").generatedFileName())
                .isEqualTo("generated.jpg");
        assertThat(AiConversationGeneratedImageFormat
                .fromContentType("image/webp").generatedFileName())
                .isEqualTo("generated.webp");
    }

    @Test
    void rejectsUnsupportedOrTruncatedBytes() {
        assertThatThrownBy(() -> AiConversationGeneratedImageFormat.detect(
                new byte[] {'R', 'I', 'F', 'F'}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> AiConversationGeneratedImageFormat.detect(
                new byte[] {'G', 'I', 'F', '8', '9', 'a'}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("format");
    }
}
