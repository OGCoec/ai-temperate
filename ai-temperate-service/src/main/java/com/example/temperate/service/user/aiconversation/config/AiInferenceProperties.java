package com.example.temperate.service.user.aiconversation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 CLIProxyAPI 推理开关、不含版本路径的基础地址、连接凭据和超时，不保存或输出 API Key。
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
            String path = uri.getPath();
            // 普通 OpenAI Starter 会单独追加 /v1/chat/completions；旧版以 /v1 结尾的地址必须启动失败，
            // 防止部署变量迁移遗漏后继续沿用 SDK 根地址语义。
            boolean legacyVersionPath = path != null
                    && path.matches("(?i).*/v1/?$");
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && !legacyVersionPath;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
