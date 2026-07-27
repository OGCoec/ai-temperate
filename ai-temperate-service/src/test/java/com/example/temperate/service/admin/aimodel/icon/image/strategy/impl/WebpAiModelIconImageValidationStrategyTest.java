package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 TwelveMonkeys Reader 能完整解码静态 WebP，并保留原始文件字节。
 */
final class WebpAiModelIconImageValidationStrategyTest {

    private static final byte[] STATIC_TWO_BY_TWO_WEBP = Base64.getDecoder().decode(
            "UklGRjgAAABXRUJQVlA4ICwAAACQAQCdASoCAAIAAgA0JaACdLoAA5gA"
                    + "/vmTb/+QH/+QH/+QH/8gP+IXeyAwAA==");

    @Test
    void fullyDecodesStaticWebp() {
        var result = new WebpAiModelIconImageValidationStrategy()
                .validate(STATIC_TWO_BY_TWO_WEBP, "image/webp");

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.WEBP);
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(2);
        assertThat(result.frameCount()).isEqualTo(1);
        assertThat(result.storageBytes()).isEqualTo(STATIC_TWO_BY_TWO_WEBP);
    }
}
