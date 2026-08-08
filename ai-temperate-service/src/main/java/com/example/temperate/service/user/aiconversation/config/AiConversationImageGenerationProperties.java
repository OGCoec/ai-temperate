package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Images Generation/Edit 路径、原图解码上限和自适应预览参数，避免 Base64 SSE 形成无界缓冲。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.image-generation")
public record AiConversationImageGenerationProperties(
        boolean enabled,
        @NotBlank String generationsPath,
        @NotBlank String editsPath,
        @Min(1) @Max(104857600) int maximumDecodedImageBytes,
        @Min(1024) @Max(10485760) int maximumInlinePreviewBytes,
        @Min(128) @Max(4096) int thumbnailMaxEdgePixels,
        @Min(1) @Max(100) int thumbnailJpegQuality,
        @Min(1048576) @Max(1073741824) long maximumPreviewRetainedBytes) {

    @AssertTrue(message = "Image generations path must be normalized")
    public boolean isGenerationsPathValid() {
        return validPath(generationsPath);
    }

    @AssertTrue(message = "Image edits path must be normalized")
    public boolean isEditsPathValid() {
        return validPath(editsPath);
    }

    private static boolean validPath(String value) {
        return value != null
                && value.startsWith("/")
                && !value.startsWith("//")
                && !value.contains("..")
                && !value.contains("?")
                && !value.contains("#");
    }

    public int maximumSseCharacters() {
        long encoded = ((long) maximumDecodedImageBytes + 2L) / 3L * 4L;
        return Math.toIntExact(encoded + 262_144L);
    }
}
