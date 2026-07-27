package com.example.temperate.service.admin.aimodel.icon.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证格式检测只依据文件签名或 SVG 文档内容，不依据文件名和 URL 后缀。
 */
final class AiModelIconImageFormatDetectorTest {

    private final AiModelIconImageFormatDetector detector =
            new AiModelIconImageFormatDetector();

    @Test
    void detectsSevenSupportedSignatures() {
        assertThat(detector.detect(new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        })).isEqualTo(AiModelIconImageFormat.PNG);
        assertThat(detector.detect(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0
        })).isEqualTo(AiModelIconImageFormat.JPEG);
        assertThat(detector.detect("GIF89a".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(AiModelIconImageFormat.GIF);
        assertThat(detector.detect(new byte[] {
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
        })).isEqualTo(AiModelIconImageFormat.WEBP);
        assertThat(detector.detect(new byte[] {0, 0, 1, 0, 1, 0}))
                .isEqualTo(AiModelIconImageFormat.ICO);
        assertThat(detector.detect(new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p',
                'a', 'v', 'i', 'f', 0, 0, 0, 0,
                'm', 'i', 'f', '1', 'a', 'v', 'i', 'f'
        })).isEqualTo(AiModelIconImageFormat.AVIF);
        assertThat(detector.detect(
                "\uFEFF  <?xml version=\"1.0\"?><svg viewBox=\"0 0 16 16\"/>"
                        .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(AiModelIconImageFormat.SVG);
    }

    @Test
    void rejectsUnknownBytesWithDedicatedUnsupportedCode() {
        assertThatThrownBy(() -> detector.detect(
                "not-an-image.png".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED));
    }
}
