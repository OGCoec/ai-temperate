package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 CLIProxyAPI 推理开关、连接凭据和超时，不保存或输出 API Key。
 */
@Validated
@ConfigurationProperties(prefix = "app.ai-inference.cli-proxy")
public record AiInferenceProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        @NotNull Duration maxStreamDuration) {

    @AssertTrue(message = "Enabled AI inference requires valid upstream configuration")
    public boolean isUpstreamConfiguredWhenEnabled() {
        return !enabled
                || (baseUrl != null
                && !baseUrl.isBlank()
                && apiKey != null
                && !apiKey.isBlank()
                && validBaseUrl(baseUrl));
    }

    @AssertTrue(message = "AI inference maximum stream duration must be positive")
    public boolean areTimeoutsValid() {
        return maxStreamDuration != null
                && !maxStreamDuration.isNegative()
                && !maxStreamDuration.isZero();
    }

    @Override
    public String toString() {
        return "AiInferenceProperties[enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", maxStreamDuration=" + maxStreamDuration + "]";
    }

    private static boolean validBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
