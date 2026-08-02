package com.example.temperate.service.user.aiconversation.attachment.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定会话附件 OSS、文件边界、模型媒体下载和音视频安全估算配置。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-conversation.attachments")
public record AiConversationAttachmentProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String endpoint,
        @NotBlank String publicBaseUrl,
        @NotNull Duration presignedPutTtl,
        @NotNull Duration signedGetTtl,
        @Min(1) @Max(104857600) long maxFileBytes,
        @Min(1) @Max(8) int maxFilesPerMessage,
        @Min(1) @Max(209715200) long maxTotalBytesPerMessage,
        @Min(1) @Max(3) int finalizationAttempts,
        @Min(1) @Max(3) int clientUploadConcurrency,
        @Min(1) @Max(65536) int audioTokensPerMib,
        @Min(0) @Max(65536) int audioFixedTokens,
        @Min(1) @Max(131072) int videoTokensPerMib,
        @Min(0) @Max(65536) int videoFixedTokens,
        @NotNull Duration mediaConnectTimeout,
        @NotNull Duration mediaReadTimeout,
        @Min(0) @Max(5) int mediaMaxRedirects) {

    public AiConversationAttachmentProperties {
        endpoint = requireHttps(endpoint, "endpoint");
        publicBaseUrl = stripTrailingSlash(requireHttps(publicBaseUrl, "publicBaseUrl"));
        requirePositive(presignedPutTtl, "presignedPutTtl");
        requirePositive(signedGetTtl, "signedGetTtl");
        requirePositive(mediaConnectTimeout, "mediaConnectTimeout");
        requirePositive(mediaReadTimeout, "mediaReadTimeout");
        if (maxTotalBytesPerMessage < maxFileBytes) {
            throw new IllegalArgumentException(
                    "maxTotalBytesPerMessage must be at least maxFileBytes");
        }
    }

    private static String requireHttps(String value, String name) {
        if (value == null || value.isBlank() || !value.trim().startsWith("https://")) {
            throw new IllegalArgumentException(name + " must be a non-blank HTTPS URL");
        }
        return value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
