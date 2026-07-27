package com.example.temperate.web.edgeproxy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 Cloudflare Worker 到后端的签名协议配置并在启动期拒绝弱密钥和无界时间窗口。
 *
 * <p>该配置只负责边缘请求真实性，不承担用户认证，也禁止复用 JWT、会话或 Redis HMAC
 * 密钥。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.edge-proxy")
public record EdgeProxyProperties(
        @NotNull EdgeProxyMode mode,
        String hmacSecretBase64,
        @NotNull Duration maxClockSkew) {

    @AssertTrue(
            message =
                    "Enabled edge proxy modes require canonical Base64 containing at least 32 bytes")
    public boolean isSecretValid() {
        if (mode == EdgeProxyMode.DISABLED) {
            return true;
        }
        if (hmacSecretBase64 == null || hmacSecretBase64.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(hmacSecretBase64);
            return decoded.length >= 32
                    && Base64.getEncoder().encodeToString(decoded)
                            .equals(hmacSecretBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "Edge proxy clock skew must be between 1 and 300 seconds")
    public boolean isClockSkewValid() {
        return maxClockSkew != null
                && maxClockSkew.compareTo(Duration.ofSeconds(1)) >= 0
                && maxClockSkew.compareTo(Duration.ofMinutes(5)) <= 0;
    }
}
