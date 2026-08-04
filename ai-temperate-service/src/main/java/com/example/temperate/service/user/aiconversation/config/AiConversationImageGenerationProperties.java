package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Images Generation 开关、相对路径和单张图片内存上限，避免 Base64 SSE 形成无界缓冲。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.image-generation")
public record AiConversationImageGenerationProperties(
        boolean enabled,
        @NotBlank String generationsPath,
        @Min(1) @Max(104857600) int maximumDecodedImageBytes) {

    @AssertTrue(message = "Image generations path must be normalized")
    public boolean isGenerationsPathValid() {
        return generationsPath != null
                && generationsPath.startsWith("/")
                && !generationsPath.startsWith("//")
                && !generationsPath.contains("..")
                && !generationsPath.contains("?")
                && !generationsPath.contains("#");
    }

    public int maximumSseCharacters() {
        long encoded = ((long) maximumDecodedImageBytes + 2L) / 3L * 4L;
        return Math.toIntExact(encoded + 262_144L);
    }
}
