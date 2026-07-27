package com.example.temperate.service.admin.aimodel.icon.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证七种模型图标格式的真实后缀和允许 MIME 别名保持稳定。
 */
final class AiModelIconImageFormatTest {

    @Test
    void exposesStableExtensionsForAllSevenFormats() {
        assertThat(AiModelIconImageFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(AiModelIconImageFormat.PNG.extension()).isEqualTo("png");
        assertThat(AiModelIconImageFormat.WEBP.extension()).isEqualTo("webp");
        assertThat(AiModelIconImageFormat.GIF.extension()).isEqualTo("gif");
        assertThat(AiModelIconImageFormat.ICO.extension()).isEqualTo("ico");
        assertThat(AiModelIconImageFormat.AVIF.extension()).isEqualTo("avif");
        assertThat(AiModelIconImageFormat.SVG.extension()).isEqualTo("svg");
    }

    @Test
    void acceptsOnlyDeclaredMimeAliases() {
        assertThat(AiModelIconImageFormat.JPEG.matchesContentType("image/jpg")).isTrue();
        assertThat(AiModelIconImageFormat.JPEG.matchesContentType(
                "image/jpeg; charset=binary")).isTrue();
        assertThat(AiModelIconImageFormat.ICO.matchesContentType("image/x-icon")).isTrue();
        assertThat(AiModelIconImageFormat.ICO.matchesContentType(
                "image/vnd.microsoft.icon")).isTrue();
        assertThat(AiModelIconImageFormat.SVG.matchesContentType(
                "image/svg+xml; charset=utf-8")).isTrue();
        assertThat(AiModelIconImageFormat.AVIF.matchesContentType("image/webp")).isFalse();
    }
}
