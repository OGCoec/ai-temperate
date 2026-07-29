package com.example.temperate.service.admin.aimodel.discovery.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定管理员通过 CLIProxyAPI 发现模型所需的服务端地址、普通代理密钥和有界读取参数。
 *
 * <p>密钥只用于后端 Bearer Header；功能关闭时允许为空，任何字符串表示都必须保持脱敏。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.admin.ai-model-discovery.cli-proxy")
public record CliProxyModelDiscoveryProperties(
        boolean enabled,
        @NotNull URI baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(500) int maxModels) {

    @AssertTrue(message = "CLIProxyAPI key must be configured when model discovery is enabled")
    public boolean isApiKeyValidForState() {
        return !enabled || (apiKey != null && !apiKey.isBlank());
    }

    @AssertTrue(message = "CLIProxyAPI base URL must be an absolute HTTP(S) origin")
    public boolean isBaseUrlValid() {
        if (baseUrl == null
                || baseUrl.getScheme() == null
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            return false;
        }
        String scheme = baseUrl.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (baseUrl.getPath() == null
                        || baseUrl.getPath().isBlank()
                        || "/".equals(baseUrl.getPath()));
    }

    @AssertTrue(message = "CLIProxyAPI timeouts must be positive")
    public boolean areTimeoutsPositive() {
        return connectTimeout != null
                && readTimeout != null
                && !connectTimeout.isZero()
                && !connectTimeout.isNegative()
                && !readTimeout.isZero()
                && !readTimeout.isNegative();
    }

    @Override
    public String toString() {
        return "CliProxyModelDiscoveryProperties[enabled="
                + enabled
                + ", baseUrl="
                + baseUrl
                + ", apiKey=redacted, connectTimeout="
                + connectTimeout
                + ", readTimeout="
                + readTimeout
                + ", maxModels="
                + maxModels
                + "]";
    }
}
